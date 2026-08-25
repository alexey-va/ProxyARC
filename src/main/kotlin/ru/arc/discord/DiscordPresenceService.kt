package ru.arc.discord

import net.dv8tion.jda.api.entities.Activity
import org.slf4j.LoggerFactory
import ru.arc.velocity.Velocity
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class DiscordPresenceService(
    private val session: DiscordSession,
    private val config: DiscordIntegrationConfig,
    private val feeds: DiscordFeedService,
    private val scheduler: ScheduledExecutorService,
) : AutoCloseable {
    @Volatile private var task: ScheduledFuture<*>? = null

    fun start() {
        if (task != null) return
        refresh()
        task =
            scheduler.scheduleWithFixedDelay(
                ::refresh,
                config.refreshSeconds,
                config.refreshSeconds,
                TimeUnit.SECONDS,
            )
    }

    private fun refresh() {
        runCatching {
            val proxy = Velocity.proxyServer ?: return
            val snapshot = DiscordNetworkSnapshot.capture(proxy)
            feeds.updateNetworkStatus(snapshot)
            if (config.presenceEnabled) {
                val activity =
                    config.presenceFormat
                        .replace("%online%", snapshot.online.toString())
                        .replace("%servers%", snapshot.knownServers.size.toString())
                        .take(128)
                session.jda()?.presence?.activity = Activity.playing(activity)
            }
        }.onFailure { error ->
            log.debug("Discord presence refresh failed: {}", error.javaClass.simpleName)
        }
    }

    override fun close() {
        task?.cancel(false)
        task = null
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordPresenceService::class.java)
    }
}
