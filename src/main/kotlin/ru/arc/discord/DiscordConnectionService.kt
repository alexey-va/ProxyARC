package ru.arc.discord

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.channel.Channel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.slf4j.LoggerFactory
import ru.arc.config.Config
import ru.arc.core.modules.LoggingModule
import ru.arc.core.modules.TransportFailureLogFilter
import ru.arc.transport.TransportOutageState
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Owns the JDA connection and publishes a ready, validated session atomically. */
internal class DiscordConnectionService(
    private val config: Config,
    private val session: DiscordSession,
    private val executor: ScheduledExecutorService,
    private val jdaFactory: ((String, DiscordProxySettings, DiscordTransportHealth) -> JDA)? = null,
) : AutoCloseable {
    private val lifecycleLock = Any()

    @Volatile
    private var enabled = false

    @Volatile
    private var jda: JDA? = null

    private val listeners = mutableListOf<Any>()
    private val startupOutage = TransportOutageState()
    private var generation = 0L
    private var retryCount = 0
    private var retryFuture: ScheduledFuture<*>? = null
    private var startupWarningLogged = false

    fun start(onReady: (DiscordSessionSnapshot) -> Collection<Any>) {
        if (!config.bool("enabled", false)) {
            log.info("Discord bot is disabled")
            return
        }
        val token = config.string("token", "token").trim()
        if (token.isEmpty() || token == "token") {
            log.error("Discord bot token is not configured")
            return
        }
        val proxySettings = DiscordProxySettings.from(config)
        val currentGeneration =
            synchronized(lifecycleLock) {
                generation++
                retryCount = 0
                enabled = true
                generation
            }
        try {
            executor.execute { buildAndAwait(currentGeneration, token, proxySettings, onReady) }
        } catch (error: RejectedExecutionException) {
            stopPermanently(currentGeneration, null, error)
        }
    }

    fun isEnabled(): Boolean = enabled

    private fun buildAndAwait(
        currentGeneration: Long,
        token: String,
        proxySettings: DiscordProxySettings,
        onReady: (DiscordSessionSnapshot) -> Collection<Any>,
    ) {
        if (!isCurrent(currentGeneration)) return

        val created =
            try {
                jdaFactory?.invoke(token, proxySettings, session.health) ?: buildJda(token, proxySettings)
            } catch (error: Exception) {
                retryOrStop(currentGeneration, null, error, token, proxySettings, onReady)
                return
            }

        val accepted =
            synchronized(lifecycleLock) {
                if (isCurrent(currentGeneration) && jda == null) {
                    jda = created
                    true
                } else {
                    false
                }
            }
        if (!accepted) {
            runCatching { created.shutdownNow() }
            return
        }

        try {
            // JDA's initial token check is synchronous inside build(); keep both build
            // and awaitReady off the Velocity event thread.
            executor.execute {
                awaitAndActivate(currentGeneration, created, token, proxySettings, onReady)
            }
        } catch (error: RejectedExecutionException) {
            retryOrStop(currentGeneration, created, error, token, proxySettings, onReady)
        }
    }

    private fun buildJda(
        token: String,
        proxySettings: DiscordProxySettings,
    ): JDA =
        JDABuilder.createDefault(token)
            .addEventListeners(session.health)
            .also { proxySettings.applyTo(it, session.health) }
            .disableCache(CacheFlag.VOICE_STATE)
            // Private staff delivery must see member-specific View Channel grants too.
            .enableCache(CacheFlag.MEMBER_OVERRIDES)
            .enableIntents(GatewayIntent.MESSAGE_CONTENT)
            .build()

    private fun awaitAndActivate(
        currentGeneration: Long,
        created: JDA,
        token: String,
        proxySettings: DiscordProxySettings,
        onReady: (DiscordSessionSnapshot) -> Collection<Any>,
    ) {
        try {
            created.awaitReady()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            if (isCurrent(currentGeneration)) stopPermanently(currentGeneration, created, IllegalStateException("Discord readiness wait was interrupted"))
            else log.info("Discord bot initialization interrupted")
            return
        } catch (error: RuntimeException) {
            retryOrStop(currentGeneration, created, error, token, proxySettings, onReady)
            return
        }

        if (!isCurrent(currentGeneration) || jda !== created) return
        val channels = resolveChannels(created)
        if (channels == null) {
            stopPermanently(
                currentGeneration,
                created,
                IllegalStateException("Discord channels.chat and channels.general must reference text channels"),
            )
            return
        }

        val snapshot = DiscordSessionSnapshot(created, channels)
        synchronized(lifecycleLock) {
            if (!isCurrent(currentGeneration) || jda !== created) return
            session.activate(created, channels)
            try {
                val readyListeners = onReady(snapshot)
                synchronized(listeners) {
                    listeners.addAll(readyListeners)
                    if (readyListeners.isNotEmpty()) created.addEventListener(*readyListeners.toTypedArray())
                }
                reportStartupRecovery()
                log.info(
                    "Discord bot ready: guilds={}, channels={}, listeners={}",
                    created.guilds.size,
                    created.textChannels.size,
                    readyListeners.size,
                )
            } catch (error: RuntimeException) {
                session.deactivate()
                stopPermanently(currentGeneration, created, error)
            }
        }
    }

    private fun retryOrStop(
        currentGeneration: Long,
        failedJda: JDA?,
        error: Throwable,
        token: String,
        proxySettings: DiscordProxySettings,
        onReady: (DiscordSessionSnapshot) -> Collection<Any>,
    ) {
        if (!isCurrent(currentGeneration)) {
            failedJda?.let { runCatching { it.shutdownNow() } }
            return
        }
        if (!isDiscordTransportFailure(error)) {
            stopPermanently(currentGeneration, failedJda, error)
            return
        }

        val delayMs =
            synchronized(lifecycleLock) {
                if (!isCurrent(currentGeneration)) return
                if (failedJda != null && jda === failedJda) jda = null
                retryCount++
                discordRetryDelayMs(retryCount)
            }
        failedJda?.let { runCatching { it.shutdownNow() } }
        if (startupOutage.failed()) {
            val libraryAlreadyLogged =
                LoggingModule.transportFailureWasLogged(TransportFailureLogFilter.JDA_LOGGER_NAME)
            if (libraryAlreadyLogged) {
                log.debug("JDA already logged the initial Discord transport failure")
            } else {
                startupWarningLogged = true
                log.warn(
                    "Discord transport unavailable during startup; retrying in {}s (cause={})",
                    TimeUnit.MILLISECONDS.toSeconds(delayMs),
                    discordFailureTypes(error),
                )
            }
        }

        try {
            val future =
                executor.schedule(
                    { buildAndAwait(currentGeneration, token, proxySettings, onReady) },
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
            synchronized(lifecycleLock) {
                if (isCurrent(currentGeneration)) retryFuture = future else future.cancel(false)
            }
        } catch (scheduleError: RejectedExecutionException) {
            stopPermanently(currentGeneration, null, scheduleError)
        }
    }

    private fun stopPermanently(
        currentGeneration: Long,
        failedJda: JDA?,
        error: Throwable,
    ) {
        val stopped =
            synchronized(lifecycleLock) {
                if (!isCurrent(currentGeneration)) return
                generation++
                enabled = false
                retryFuture?.cancel(false)
                retryFuture = null
                retryCount = 0
                if (jda === failedJda) jda = null
                session.deactivate()
                true
            }
        if (!stopped) return

        val ownRepeats = startupOutage.recovered() ?: 0
        val loggerName = TransportFailureLogFilter.JDA_LOGGER_NAME
        val libraryRepeats = LoggingModule.transportRecovered(loggerName)
        failedJda?.let { runCatching { it.shutdownNow() } }
        log.error(
            "Discord startup stopped until Velocity restart (cause={}, coalescedRetries={}, coalescedJdaLogs={})",
            discordFailureTypes(error),
            ownRepeats,
            libraryRepeats,
            error,
        )
        startupWarningLogged = false
    }

    private fun reportStartupRecovery() {
        val repeats = startupOutage.recovered()
        val hadLibraryFailure =
            LoggingModule.transportFailureWasLogged(TransportFailureLogFilter.JDA_LOGGER_NAME)
        val libraryRepeats = LoggingModule.transportRecovered(TransportFailureLogFilter.JDA_LOGGER_NAME)
        if (startupWarningLogged) {
            log.info(
                "Discord startup transport recovered; repeated failures coalesced={} (startup={}, JDA={})",
                (repeats ?: 0) + libraryRepeats,
                repeats ?: 0,
                libraryRepeats,
            )
        } else if (hadLibraryFailure && libraryRepeats > 0) {
            log.info("Discord startup transport recovered; repeated JDA failures coalesced={}", libraryRepeats)
        }
        startupWarningLogged = false
    }

    private fun resolveChannels(jda: JDA): DiscordChannels? {
        val chat = resolveTextChannel(jda, "channels.chat", "chat")
        val general = resolveTextChannel(jda, "channels.general", "general")
        if (chat == null || general == null) {
            log.error("Discord bot is not ready: channels.chat and channels.general must reference text channels")
            return null
        }
        return DiscordChannels(
            join = resolveTextChannel(jda, "channels.join-messages", "join"),
            playerList = resolveTextChannel(jda, "channels.player-list", "player-list"),
            auction = resolveTextChannel(jda, "channels.auction", "auction"),
            chat = chat,
            general = general,
            issueTickets = resolveOptionalChannel(jda, "channels.issue-tickets", "issue-tickets"),
        )
    }

    private fun resolveTextChannel(
        jda: JDA,
        path: String,
        label: String,
    ): TextChannel? {
        val id = config.string(path, "none").trim()
        if (id == "none") return null
        val channel = jda.getGuildChannelById(id) as? TextChannel
        if (channel == null) log.warn("Discord {} channel is missing or is not a text channel", label)
        return channel
    }

    private fun resolveOptionalChannel(
        jda: JDA,
        path: String,
        label: String,
    ): Channel? {
        val id = config.string(path, "none").trim()
        if (id == "none") return null
        return jda.getGuildChannelById(id).also {
            if (it == null) log.warn("Discord {} channel is missing", label)
        }
    }

    private fun isCurrent(candidate: Long): Boolean = synchronized(lifecycleLock) { enabled && generation == candidate }

    override fun close() {
        val current =
            synchronized(lifecycleLock) {
                generation++
                enabled = false
                retryFuture?.cancel(false)
                retryFuture = null
                retryCount = 0
                val currentJda = jda
                jda = null
                session.deactivate()
                currentJda
            }
        synchronized(listeners) {
            if (listeners.isNotEmpty()) {
                runCatching { current?.removeEventListener(*listeners.toTypedArray()) }
            }
            listeners.clear()
        }
        runCatching { current?.shutdownNow() }
            .onFailure { log.warn("Failed to stop Discord JDA cleanly", it) }
        startupOutage.reset()
        startupWarningLogged = false
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordConnectionService::class.java)
    }
}

internal fun discordRetryDelayMs(retryNumber: Int): Long {
    require(retryNumber > 0)
    return (2_000L shl (retryNumber - 1).coerceAtMost(5)).coerceAtMost(60_000L)
}

internal fun isDiscordTransportFailure(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    if (causes.any { it is javax.net.ssl.SSLException }) return false
    val httpStatus = causes.filterIsInstance<ErrorResponseException>().firstOrNull()?.response?.code ?: 0
    if (httpStatus > 0) return httpStatus == 429 || httpStatus >= 500
    return causes.any { it is java.io.IOException }
}

internal fun discordFailureTypes(error: Throwable): String =
    generateSequence(error) { it.cause }
        .take(8)
        .map { cause -> cause.javaClass.simpleName.ifBlank { cause.javaClass.name } }
        .distinct()
        .joinToString(" <- ")
