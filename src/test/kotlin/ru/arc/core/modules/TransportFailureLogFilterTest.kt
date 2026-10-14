package ru.arc.core.modules

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.apache.http.auth.AuthenticationException
import org.apache.http.conn.ConnectTimeoutException
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.DefaultConfiguration
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.impl.Log4jLogEvent
import org.apache.logging.log4j.message.ParameterizedMessage
import org.apache.logging.log4j.message.SimpleMessage
import java.net.ConnectException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.NoRouteToHostException
import java.net.SocketException
import javax.net.ssl.SSLHandshakeException

class TransportFailureLogFilterTest : FreeSpec({
    "coalesces known transport events and reports their repeats on recovery" - {
        var now = 0L
        val filter = TransportFailureLogFilter(nanoTime = { now })

        filter.filter(jdaEvent("Connect timed out")) shouldBe Filter.Result.NEUTRAL
        filter.transportFailureWasLogged(TransportFailureLogFilter.JDA_LOGGER_NAME) shouldBe true
        filter.filter(jdaEvent("Connect timed out")) shouldBe Filter.Result.DENY
        filter.transportRecovered(TransportFailureLogFilter.JDA_LOGGER_NAME) shouldBe 1
        filter.transportFailureWasLogged(TransportFailureLogFilter.JDA_LOGGER_NAME) shouldBe false
        filter.filter(jdaEvent("Connect timed out")) shouldBe Filter.Result.NEUTRAL
    }

    "lets one event through again after the five-minute window" - {
        var now = 0L
        val filter = TransportFailureLogFilter(nanoTime = { now })

        filter.filter(jdaEvent("Connection reset")) shouldBe Filter.Result.NEUTRAL
        filter.filter(jdaEvent("Connection reset")) shouldBe Filter.Result.DENY
        now = TransportFailureLogFilter.SUPPRESSION_WINDOW_NANOS
        filter.filter(jdaEvent("Connection reset")) shouldBe Filter.Result.NEUTRAL
        filter.transportRecovered(TransportFailureLogFilter.JDA_LOGGER_NAME) shouldBe 1
    }

    "coalesces OkHttp connect-failure text and leaves arbitrary JDA I/O text visible" - {
        val filter = TransportFailureLogFilter(nanoTime = { 0L })
        val refused = jdaEvent("Failed to connect to /127.0.0.1:18888")
        val unknown = jdaEvent("I/O operation failed while processing response")

        filter.filter(refused) shouldBe Filter.Result.NEUTRAL
        filter.filter(refused) shouldBe Filter.Result.DENY
        filter.filter(unknown) shouldBe Filter.Result.NEUTRAL
        filter.filter(unknown) shouldBe Filter.Result.NEUTRAL
        filter.transportRecovered(TransportFailureLogFilter.JDA_LOGGER_NAME) shouldBe 1
    }

    "tracks Telegram connect timeouts separately and requires the typed cause" - {
        val filter = TransportFailureLogFilter(nanoTime = { 0L })
        val telegram = telegramEvent(ConnectTimeoutException("connect timed out"))

        filter.filter(telegram) shouldBe Filter.Result.NEUTRAL
        filter.filter(telegram) shouldBe Filter.Result.DENY
        filter.filter(telegramEvent(IOException("bad token"))) shouldBe Filter.Result.NEUTRAL
        filter.transportFailureWasLogged(TransportFailureLogFilter.TELEGRAM_LOGGER_NAME) shouldBe true
        filter.transportRecovered(TransportFailureLogFilter.TELEGRAM_LOGGER_NAME) shouldBe 1
    }

    "coalesces Telegram refused connections and read timeouts" - {
        val filter = TransportFailureLogFilter(nanoTime = { 0L })
        val refused = telegramEvent(ConnectException("Connection refused"))
        val readTimeout = telegramEvent(SocketTimeoutException("Read timed out"), level = Level.INFO)

        filter.filter(refused) shouldBe Filter.Result.NEUTRAL
        filter.filter(refused) shouldBe Filter.Result.DENY
        filter.filter(readTimeout) shouldBe Filter.Result.NEUTRAL
        filter.filter(readTimeout) shouldBe Filter.Result.DENY
        filter.transportRecovered(TransportFailureLogFilter.TELEGRAM_LOGGER_NAME) shouldBe 2
    }

    "coalesces typed unreachable and exact transient socket failures" - {
        val filter = TransportFailureLogFilter(nanoTime = { 0L })
        val noRoute = telegramEvent(NoRouteToHostException("No route to host"))
        val networkUnreachable = telegramEvent(SocketException("Network is unreachable"))
        val connectionReset = telegramEvent(SocketException("Connection reset by peer"))
        val socketClosed = telegramEvent(SocketException("Socket closed"))

        filter.filter(noRoute) shouldBe Filter.Result.NEUTRAL
        filter.filter(networkUnreachable) shouldBe Filter.Result.DENY
        filter.filter(networkUnreachable) shouldBe Filter.Result.DENY
        filter.filter(connectionReset) shouldBe Filter.Result.NEUTRAL
        filter.filter(connectionReset) shouldBe Filter.Result.DENY
        filter.filter(socketClosed) shouldBe Filter.Result.NEUTRAL
        filter.filter(socketClosed) shouldBe Filter.Result.DENY
        filter.filter(telegramEvent(SocketException("Permission denied"))) shouldBe Filter.Result.NEUTRAL
        filter.transportRecovered(TransportFailureLogFilter.TELEGRAM_LOGGER_NAME) shouldBe 4
    }

    "passes unrecognized messages, throwable-bearing JDA events, other loggers, and non-errors" - {
        val filter = TransportFailureLogFilter(nanoTime = { 0L })
        val tlsFailure = SSLHandshakeException("TLS failed")
        tlsFailure.initCause(ConnectException("Connection refused"))
        val refusedWithTlsCause = ConnectException("Connection refused")
        refusedWithTlsCause.initCause(SSLHandshakeException("TLS failed"))

        filter.filter(jdaEvent("Remote host terminated the handshake")) shouldBe Filter.Result.NEUTRAL
        filter.filter(jdaEvent("Connection reset", SSLHandshakeException("TLS failed"))) shouldBe Filter.Result.NEUTRAL
        filter.filter(jdaEvent("Connect timed out", loggerName = "other.Requester")) shouldBe Filter.Result.NEUTRAL
        filter.filter(telegramEvent(ConnectTimeoutException("connect timed out"), level = Level.INFO)) shouldBe Filter.Result.NEUTRAL
        filter.filter(telegramEvent(tlsFailure)) shouldBe Filter.Result.NEUTRAL
        filter.filter(telegramEvent(refusedWithTlsCause)) shouldBe Filter.Result.NEUTRAL
        filter.filter(telegramEvent(AuthenticationException("credentials rejected"))) shouldBe Filter.Result.NEUTRAL
        filter.filter(telegramEvent(IllegalArgumentException("bad config", ConnectException("Connection refused")))) shouldBe
            Filter.Result.NEUTRAL
    }

    "coalesces a real formatted logger event before it reaches the appender" - {
        val context = LoggerContext("transport-filter-test")
        val configuration = DefaultConfiguration()
        configuration.rootLogger.appenders.keys.toList().forEach(configuration.rootLogger::removeAppender)
        val appender = CapturingAppender()
        configuration.addAppender(appender)
        configuration.rootLogger.addAppender(appender, Level.ALL, null)
        val jdaChildConfig =
            LoggerConfig("net.dv8tion.jda.internal.requests", Level.ERROR, true)
        configuration.addLogger("net.dv8tion.jda.internal.requests", jdaChildConfig)
        val loggerConfigs = transportFailureFilterLoggerConfigs(configuration)
        loggerConfigs shouldBe setOf(configuration.rootLogger)

        val filter = TransportFailureLogFilter(nanoTime = { 0L })
        filter.start()
        try {
            context.start(configuration)
            loggerConfigs.forEach { it.addFilter(filter) }
            context.updateLoggers()

            val jdaLogger = context.getLogger(TransportFailureLogFilter.JDA_LOGGER_NAME)
            jdaLogger.error("There was an I/O error while executing a REST request: {}", "Connect timed out")
            jdaLogger.error("There was an I/O error while executing a REST request: {}", "Connect timed out")
            jdaLogger.error(
                "There was an I/O error while executing a REST request: {}",
                "Failed to connect to /127.0.0.1:18888",
            )
            jdaLogger.error(
                "There was an I/O error while executing a REST request: {}",
                "Failed to connect to /127.0.0.1:18888",
            )

            val telegramLogger = context.getLogger(TransportFailureLogFilter.TELEGRAM_LOGGER_NAME)
            telegramLogger.error("Error getting updates", ConnectTimeoutException("connect timed out"))
            telegramLogger.error("Error getting updates", ConnectTimeoutException("connect timed out"))
            telegramLogger.error("Error getting updates", ConnectException("Connection refused"))
            telegramLogger.error("Error getting updates", ConnectException("Connection refused"))

            appender.events.size shouldBe 4
            appender.events.count { it.loggerName == TransportFailureLogFilter.JDA_LOGGER_NAME } shouldBe 2
            appender.events.count { it.loggerName == TransportFailureLogFilter.TELEGRAM_LOGGER_NAME } shouldBe 2
            appender.events.first { it.loggerName == TransportFailureLogFilter.JDA_LOGGER_NAME }
                .message.formattedMessage shouldBe
                "There was an I/O error while executing a REST request: Connect timed out"
            appender.events.any {
                it.loggerName == TransportFailureLogFilter.JDA_LOGGER_NAME &&
                    it.message.formattedMessage.endsWith("Failed to connect to /127.0.0.1:18888")
            } shouldBe true
        } finally {
            loggerConfigs.forEach { it.removeFilter(filter) }
            context.updateLoggers()
            filter.stop()
            context.close()
        }
    }
})

private fun jdaEvent(
    detail: String,
    thrown: Throwable? = null,
    loggerName: String = TransportFailureLogFilter.JDA_LOGGER_NAME,
    level: Level = Level.ERROR,
): LogEvent =
    Log4jLogEvent.newBuilder()
        .setLoggerName(loggerName)
        .setLevel(level)
        .setMessage(ParameterizedMessage("There was an I/O error while executing a REST request: {}", detail))
        .setThrown(thrown)
        .build()

private fun telegramEvent(
    thrown: Throwable,
    level: Level = Level.ERROR,
): LogEvent =
    Log4jLogEvent.newBuilder()
        .setLoggerName(TransportFailureLogFilter.TELEGRAM_LOGGER_NAME)
        .setLevel(level)
        .setMessage(SimpleMessage("connect timed out"))
        .setThrown(thrown)
        .build()

private class CapturingAppender : AbstractAppender("transport-filter-test-appender", null, null, true) {
    val events = mutableListOf<LogEvent>()

    override fun append(event: LogEvent) {
        events += event.toImmutable()
    }
}
