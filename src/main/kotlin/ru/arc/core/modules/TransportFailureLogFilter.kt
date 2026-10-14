package ru.arc.core.modules

import org.apache.http.auth.AuthenticationException
import org.apache.http.conn.ConnectTimeoutException
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.filter.AbstractFilter
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import javax.net.ssl.SSLException

/** Coalesces only the recurring transport failures emitted by the installed bot libraries. */
internal class TransportFailureLogFilter(
    private val nanoTime: () -> Long = System::nanoTime,
    private val suppressionWindowNanos: Long = SUPPRESSION_WINDOW_NANOS,
) : AbstractFilter() {
    private val lock = Any()

    // The key space is bounded by the two exact library logger names and FailureKind.
    private val states = mutableMapOf<FailureKey, FailureState>()

    init {
        require(suppressionWindowNanos > 0L) { "Suppression window must be positive" }
    }

    override fun filter(event: LogEvent): Filter.Result {
        val key = classify(event) ?: return Filter.Result.NEUTRAL
        val now = nanoTime()

        synchronized(lock) {
            val state = states[key]
            if (state == null) {
                states[key] = FailureState(lastForwardedAtNanos = now)
                return Filter.Result.NEUTRAL
            }

            if (now - state.lastForwardedAtNanos >= suppressionWindowNanos) {
                state.lastForwardedAtNanos = now
                return Filter.Result.NEUTRAL
            }

            if (state.suppressedCount < Int.MAX_VALUE) {
                state.suppressedCount++
            }
            return Filter.Result.DENY
        }
    }

    /** True when at least one matching original library event has been allowed through. */
    fun transportFailureWasLogged(loggerName: String): Boolean = synchronized(lock) {
        states.keys.any { it.loggerName == loggerName }
    }

    /** Return the number of coalesced repeats for this logger and clear its outage state. */
    fun transportRecovered(loggerName: String): Int = synchronized(lock) {
        val matchingKeys = states.keys.filter { it.loggerName == loggerName }
        var suppressed = 0
        matchingKeys.forEach { key ->
            val state = states.remove(key) ?: return@forEach
            suppressed =
                (suppressed.toLong() + state.suppressedCount)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
        }
        suppressed
    }

    private fun classify(event: LogEvent): FailureKey? {
        return when (event.loggerName) {
            JDA_LOGGER_NAME -> if (event.level == Level.ERROR) classifyJda(event) else null
            TELEGRAM_LOGGER_NAME -> classifyTelegram(event)
            else -> null
        }
    }

    private fun classifyJda(event: LogEvent): FailureKey? {
        // JDA 6.5.0 logs this as a formatted message argument, without a throwable.
        if (event.thrown != null || event.thrownProxy != null) return null
        val message = event.message?.formattedMessage ?: return null
        if (!message.startsWith(JDA_IO_ERROR_PREFIX)) return null

        val detail = message.removePrefix(JDA_IO_ERROR_PREFIX).trim().lowercase(Locale.ROOT)
        if (
            detail.startsWith(OKHTTP_CONNECT_FAILURE_PREFIX) &&
                detail.removePrefix(OKHTTP_CONNECT_FAILURE_PREFIX).isNotBlank()
        ) {
            return FailureKey(JDA_LOGGER_NAME, FailureKind.CONNECT_FAILURE)
        }
        val kind =
            when (detail) {
                "connect timed out", "connection timed out" -> FailureKind.CONNECT_TIMEOUT
                "read timed out", "socket timed out" -> FailureKind.READ_TIMEOUT
                "connection refused" -> FailureKind.CONNECTION_REFUSED
                "connection reset", "connection reset by peer" -> FailureKind.CONNECTION_RESET
                "no route to host", "network is unreachable", "host is unreachable" -> FailureKind.NETWORK_UNREACHABLE
                "socket closed", "socket is closed" -> FailureKind.SOCKET_CLOSED
                else -> return null
            }
        return FailureKey(JDA_LOGGER_NAME, kind)
    }

    private fun classifyTelegram(event: LogEvent): FailureKey? {
        if (event.level != Level.ERROR && event.level != Level.INFO) return null
        val throwable = event.thrown ?: event.thrownProxy?.throwable
        val kind = telegramFailureKind(throwable) ?: return null
        return FailureKey(TELEGRAM_LOGGER_NAME, kind)
    }

    private fun telegramFailureKind(throwable: Throwable?): FailureKind? {
        var current = throwable
        var depth = 0
        var transportKind: FailureKind? = null
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            // Leave TLS, auth/API, and common caller errors visible even if they wrap I/O causes.
            if (
                current is SSLException ||
                    current is AuthenticationException ||
                    current is TelegramApiRequestException ||
                    current is RuntimeException
            ) {
                return null
            }

            val currentTransportKind =
                when (current) {
                    is ConnectTimeoutException -> FailureKind.CONNECT_TIMEOUT
                    is SocketTimeoutException -> FailureKind.READ_TIMEOUT
                    is NoRouteToHostException -> FailureKind.NETWORK_UNREACHABLE
                    is ConnectException -> classifyConnectException(current.message)
                    is SocketException -> classifySocketException(current.message)
                    else -> null
                }
            if (transportKind == null && currentTransportKind != null) {
                transportKind = currentTransportKind
            }

            current = current.cause
            depth++
        }
        return transportKind
    }

    private fun classifyConnectException(message: String?): FailureKind {
        val detail = message.orEmpty().lowercase(Locale.ROOT)
        return when {
            "connection refused" in detail -> FailureKind.CONNECTION_REFUSED
            "connection timed out" in detail || "connect timed out" in detail -> FailureKind.CONNECT_TIMEOUT
            "no route to host" in detail || "network is unreachable" in detail || "host is unreachable" in detail ->
                FailureKind.NETWORK_UNREACHABLE
            else -> FailureKind.CONNECT_FAILURE
        }
    }

    private fun classifySocketException(message: String?): FailureKind? =
        when (message.orEmpty().trim().lowercase(Locale.ROOT)) {
            "connection reset", "connection reset by peer" -> FailureKind.CONNECTION_RESET
            "socket closed", "socket is closed" -> FailureKind.SOCKET_CLOSED
            "no route to host", "network is unreachable", "host is unreachable" -> FailureKind.NETWORK_UNREACHABLE
            "broken pipe" -> FailureKind.BROKEN_PIPE
            else -> null
        }

    private data class FailureKey(val loggerName: String, val kind: FailureKind)

    private data class FailureState(
        var lastForwardedAtNanos: Long,
        var suppressedCount: Int = 0,
    )

    private enum class FailureKind {
        CONNECT_TIMEOUT,
        CONNECT_FAILURE,
        READ_TIMEOUT,
        CONNECTION_REFUSED,
        CONNECTION_RESET,
        NETWORK_UNREACHABLE,
        SOCKET_CLOSED,
        BROKEN_PIPE,
    }

    companion object {
        const val JDA_LOGGER_NAME = "net.dv8tion.jda.internal.requests.Requester"
        const val TELEGRAM_LOGGER_NAME = "org.telegram.telegrambots.updatesreceivers.DefaultBotSession"
        private const val JDA_IO_ERROR_PREFIX = "There was an I/O error while executing a REST request: "
        private const val OKHTTP_CONNECT_FAILURE_PREFIX = "failed to connect to "
        private const val MAX_CAUSE_DEPTH = 16
        internal const val SUPPRESSION_WINDOW_NANOS = 5L * 60L * 1_000_000_000L
    }
}
