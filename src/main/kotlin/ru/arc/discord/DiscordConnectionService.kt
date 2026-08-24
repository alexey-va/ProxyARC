package ru.arc.discord

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.channel.Channel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.slf4j.LoggerFactory
import ru.arc.config.Config
import java.util.concurrent.ExecutorService

/** Owns the JDA connection and publishes a ready, validated session atomically. */
internal class DiscordConnectionService(
    private val config: Config,
    private val session: DiscordSession,
    private val executor: ExecutorService,
) : AutoCloseable {
    @Volatile
    private var enabled = false
    @Volatile
    private var jda: JDA? = null
    private val listeners = mutableListOf<Any>()

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

        val created =
            JDABuilder.createDefault(token)
                .also { DiscordProxySettings.from(config).applyTo(it) }
                .disableCache(CacheFlag.VOICE_STATE, CacheFlag.MEMBER_OVERRIDES)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .build()
        jda = created
        enabled = true
        executor.execute { awaitAndActivate(created, onReady) }
    }

    fun isEnabled(): Boolean = enabled

    private fun awaitAndActivate(
        created: JDA,
        onReady: (DiscordSessionSnapshot) -> Collection<Any>,
    ) {
        try {
            created.awaitReady()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            log.info("Discord bot initialization interrupted")
            return
        } catch (error: RuntimeException) {
            if (enabled) log.error("Discord bot failed while waiting for readiness", error)
            return
        }
        if (!enabled || jda !== created) return

        val channels = resolveChannels(created) ?: return
        val snapshot = DiscordSessionSnapshot(created, channels)
        session.activate(created, channels)
        try {
            val readyListeners = onReady(snapshot)
            synchronized(listeners) {
                listeners.addAll(readyListeners)
                if (readyListeners.isNotEmpty()) {
                    created.addEventListener(*readyListeners.toTypedArray())
                }
            }
            log.info(
                "Discord bot ready: guilds={}, channels={}, listeners={}",
                created.guilds.size,
                created.textChannels.size,
                readyListeners.size,
            )
        } catch (error: RuntimeException) {
            session.deactivate()
            log.error("Discord services failed to activate", error)
        }
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

    @Synchronized
    override fun close() {
        if (!enabled && jda == null) return
        enabled = false
        session.deactivate()
        val current = jda
        synchronized(listeners) {
            if (listeners.isNotEmpty()) {
                runCatching { current?.removeEventListener(*listeners.toTypedArray()) }
            }
            listeners.clear()
        }
        runCatching { current?.shutdownNow() }
            .onFailure { log.warn("Failed to stop Discord JDA cleanly", it) }
        jda = null
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordConnectionService::class.java)
    }
}
