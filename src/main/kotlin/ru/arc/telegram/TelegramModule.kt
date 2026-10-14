package ru.arc.telegram

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.exceptions.TelegramApiValidationException
import org.telegram.telegrambots.meta.generics.BotSession
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import ru.arc.config.ProxyConfigs
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.velocity.Velocity
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

private val log = LoggerFactory.getLogger(TelegramModule::class.java)

// ==================== Priority 75: Telegram ====================

object TelegramModule : PluginModule {
    override val name = "Telegram"
    override val priority = 75

    private val runtime =
        TelegramRuntime(
            register = { bot -> TelegramBotsApi(DefaultBotSession::class.java).registerBot(bot) },
            scheduler = { Tasks.scheduler },
            onPermanentFailure = { bot ->
                if (Velocity.telegramBot === bot) Velocity.telegramBot = null
            },
        )

    override fun init() {
        shutdown()
        try {
            val telegramConfig = TelegramConfig.load()
            if (!telegramConfig.enabled) {
                log.info("TelegramBot is disabled in config")
                return
            }
            telegramConfig.validate()
            val proxySettings = TelegramProxySettings.from(ProxyConfigs.module("llm-network.yml"))
            val identity =
                if (telegramConfig.identityEnabled) {
                    runCatching {
                        TelegramIdentityService(
                            TelegramIdentityStore(Velocity.requireDataFolder()),
                            telegramConfig,
                        )
                    }.onFailure { error ->
                        log.error("Telegram identity service is unavailable", error)
                    }.getOrNull()
                } else {
                    null
                }
            val bot =
                TelegramBot(
                    telegramConfig,
                    identityService = identity,
                    botOptions = proxySettings.botOptions(),
                )
            Velocity.telegramBot = bot
            runtime.start(bot)
            log.info("TelegramBot startup scheduled proxy={}", proxySettings.enabled)
        } catch (e: Exception) {
            runtime.close()
            Velocity.telegramBot = null
            log.error("Error while initializing TelegramBot", e)
        }
    }

    override fun shutdown() {
        runtime.close()
        Velocity.telegramBot = null
    }

    override fun reload() {
        init()
    }
}

internal class TelegramRuntime(
    private val register: (TelegramBot) -> BotSession,
    private val scheduler: () -> TaskScheduler = { Tasks.scheduler },
    private val probe: (TelegramBot) -> CompletableFuture<Unit> = TelegramBot::probeConnectivity,
    private val onPermanentFailure: (TelegramBot) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private var generation = 0L
    private var bot: TelegramBot? = null
    private var session: BotSession? = null
    private var scheduled: ScheduledTask? = null
    private var inFlight = false
    private var retryCount = 0

    fun start(newBot: TelegramBot): TelegramBot {
        close()
        val currentGeneration =
            synchronized(lock) {
                bot = newBot
                retryCount = 0
                generation += 1
                generation
            }
        scheduleAttempt(currentGeneration, delayMs = 0)
        return newBot
    }

    private fun runAttempt(currentGeneration: Long) {
        val activeBot =
            synchronized(lock) {
                if (!isCurrent(currentGeneration)) return
                scheduled = null
                if (inFlight) return
                inFlight = true
                bot ?: return
            }

        var activeSession = synchronized(lock) { session }
        if (activeSession != null && !runCatching { activeSession.isRunning }.getOrDefault(false)) {
            synchronized(lock) {
                if (session === activeSession) session = null
            }
            stopSession(activeSession)
            activeSession = null
        }

        if (activeSession == null) {
            val registered =
                try {
                    register(activeBot)
                } catch (error: Exception) {
                    handleFailure(currentGeneration, activeBot, error, registrationFailure = true)
                    return
                }
            val accepted =
                synchronized(lock) {
                    if (isCurrent(currentGeneration) && bot === activeBot) {
                        session = registered
                        true
                    } else {
                        false
                    }
                }
            if (!accepted) {
                stopSession(registered)
                return
            }
        }

        try {
            probe(activeBot).whenComplete { _, error ->
                if (error == null) connected(currentGeneration, activeBot)
                else handleFailure(currentGeneration, activeBot, error, registrationFailure = false)
            }
        } catch (error: Exception) {
            handleFailure(currentGeneration, activeBot, error, registrationFailure = false)
        }
    }

    private fun connected(
        currentGeneration: Long,
        activeBot: TelegramBot,
    ) {
        val active =
            synchronized(lock) {
                if (!isCurrent(currentGeneration) || bot !== activeBot) return
                inFlight = false
                retryCount = 0
                true
            }
        if (!active) return
        activeBot.markProbeSucceeded()
        scheduleAttempt(currentGeneration, delayMs = HEALTH_CHECK_INTERVAL_MS)
    }

    private fun handleFailure(
        currentGeneration: Long,
        activeBot: TelegramBot,
        error: Throwable,
        registrationFailure: Boolean,
    ) {
        val delay =
            synchronized(lock) {
                if (!isCurrent(currentGeneration) || bot !== activeBot) return
                inFlight = false
                if (!isTelegramTransportFailure(error)) return@synchronized null
                retryCount++
                telegramRetryDelayMs(retryCount)
            }
        if (delay == null) {
            failPermanently(currentGeneration, activeBot, error)
            return
        }
        if (registrationFailure) activeBot.recordTransportFailure(error, delay)
        scheduleAttempt(currentGeneration, delay)
    }

    private fun failPermanently(
        currentGeneration: Long,
        activeBot: TelegramBot,
        error: Throwable,
    ) {
        val activeSession =
            synchronized(lock) {
                if (!isCurrent(currentGeneration) || bot !== activeBot) return
                generation++
                scheduled?.cancel()
                scheduled = null
                inFlight = false
                retryCount = 0
                bot = null
                session.also { session = null }
            }
        activeBot.close()
        activeSession?.let(::stopSession)
        log.error(
            "Telegram startup/health checks stopped until reload (cause={})",
            telegramFailureTypes(error),
        )
        runCatching { onPermanentFailure(activeBot) }
            .onFailure { callbackError -> log.error("Failed to clear stopped Telegram bot", callbackError) }
    }

    private fun scheduleAttempt(
        currentGeneration: Long,
        delayMs: Long,
    ) {
        var schedulingError: RuntimeException? = null
        synchronized(lock) {
            if (!isCurrent(currentGeneration)) return
            scheduled?.cancel()
            try {
                val delayTicks = if (delayMs == 0L) 0L else (delayMs + TICK_MS - 1) / TICK_MS
                scheduled = scheduler().runLaterAsync(delayTicks) { runAttempt(currentGeneration) }
            } catch (error: RuntimeException) {
                scheduled = null
                schedulingError = error
            }
        }
        schedulingError?.let { error ->
            val activeBot = synchronized(lock) { bot?.takeIf { isCurrent(currentGeneration) } } ?: return
            failPermanently(currentGeneration, activeBot, error)
        }
    }

    private fun stopSession(activeSession: BotSession) {
        runCatching {
            if (activeSession.isRunning) activeSession.stop()
        }.onFailure { error -> log.warn("Failed to stop Telegram bot session", error) }
    }

    private fun isCurrent(candidate: Long): Boolean = generation == candidate && bot != null

    override fun close() {
        val (activeBot, activeSession) =
            synchronized(lock) {
                generation++
                scheduled?.cancel()
                scheduled = null
                inFlight = false
                retryCount = 0
                val previousBot = bot
                val previousSession = session
                bot = null
                session = null
                previousBot to previousSession
            }
        activeBot?.close()
        activeSession?.let(::stopSession)
    }

    companion object {
        private const val TICK_MS = 50L
        private const val BASE_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 60_000L
    }
}

internal fun telegramRetryDelayMs(retryNumber: Int): Long {
    require(retryNumber > 0)
    return (2_000L shl (retryNumber - 1).coerceAtMost(5)).coerceAtMost(60_000L)
}

internal fun isTelegramTransportFailure(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    val apiErrorCode =
        causes.filterIsInstance<TelegramApiRequestException>()
            .mapNotNull { it.errorCode?.takeIf { code -> code > 0 } }
            .firstOrNull() ?: 0
    if (apiErrorCode > 0) {
        return apiErrorCode == 429 || apiErrorCode >= 500
    }
    if (causes.any { it is TelegramApiValidationException || it is SSLException }) return false
    return causes.any { it is IOException }
}

internal fun telegramFailureTypes(error: Throwable): String =
    generateSequence(error) { it.cause }
        .take(8)
        .map { cause ->
            val type = cause.javaClass.simpleName.ifBlank { cause.javaClass.name }
            if (cause is TelegramApiRequestException && cause.errorCode > 0) "$type[code=${cause.errorCode}]" else type
        }
        .distinct()
        .joinToString(" <- ")
