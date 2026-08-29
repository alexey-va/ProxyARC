package ru.arc.core.modules

import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.core.repeating
import ru.arc.join.JoinAnnouncementConfig
import ru.arc.join.JoinAnnouncementService
import ru.arc.join.RedisJoinMessageSource
import ru.arc.join.VelocityJoinAnnouncementSink
import ru.arc.join.VelocityProxyLifecycle
import ru.arc.velocity.Velocity
import ru.arc.velocity.listeners.ChatListener
import ru.arc.velocity.listeners.JoinListener
import ru.arc.velocity.listeners.RestartKickListener
import java.util.concurrent.atomic.AtomicInteger

// ==================== Priority 90-99: Runtime ====================

object ListenersModule : PluginModule {
    override val name = "Listeners"
    override val priority = 90

    override fun init() {
        val plugin = Velocity.requirePlugin()
        val server = Velocity.requireProxyServer()
        val announcementService =
            JoinAnnouncementService(
                firstJoinData = checkNotNull(Velocity.firstJoinData) { "First-join data is not initialized" },
                messageSource = RedisJoinMessageSource(),
                sink =
                    VelocityJoinAnnouncementSink(
                        proxyServer = server,
                        config = JoinAnnouncementConfig.load(Velocity.requireDataFolder()),
                        logger = Velocity.requireLogger(),
                    ),
                scheduler = Tasks.scheduler,
                lifecycle = VelocityProxyLifecycle,
            )
        server.eventManager.register(
            plugin,
            JoinListener(announcementService),
        )
        server.eventManager.register(
            plugin,
            ChatListener(server),
        )
        server.eventManager.register(
            plugin,
            RestartKickListener(),
        )
    }

    override fun shutdown() {}

    /** Avoid duplicate Velocity event handler registration on reload. */
    override fun reload() {}
}

object ProxyTasksModule : PluginModule {
    override val name = "ProxyTasks"
    override val priority = 95

    private var playerListTask: ScheduledTask? = null
    private var redisPlayerListTask: ScheduledTask? = null
    private var playerActivityTask: ScheduledTask? = null
    private val counter = AtomicInteger(0)

    override fun init() {
        val plugin = Velocity.requirePlugin()
        val server = Velocity.requireProxyServer()
        playerListTask =
            repeating(0, 1200) {
                Velocity.discordBot?.updatePlayerList(plugin.onlinePlayerNames())
            }

        redisPlayerListTask =
            repeating(0, 20) {
                Velocity.playerListAnnouncer?.announce()
                if (counter.incrementAndGet() % 120 == 0) {
                    val players = server.allPlayers
                    Velocity.playerListAnnouncer?.removeAllPlayers()
                    players.forEach { player ->
                        Velocity.playerListAnnouncer?.addPlayer(
                            player.uniqueId,
                            player.username,
                            player.currentServer.map { it.serverInfo.name }.orElse(""),
                        )
                    }
                }
            }

        playerActivityTask =
            repeating(0, PLAYER_ACTIVITY_HEARTBEAT_TICKS) {
                Velocity.playerActivityTracker?.markSeenAll(server.allPlayers.map { it.uniqueId })
            }
    }

    override fun shutdown() {
        playerListTask?.cancel()
        redisPlayerListTask?.cancel()
        playerActivityTask?.cancel()
        playerListTask = null
        redisPlayerListTask = null
        playerActivityTask = null
        counter.set(0)
    }

    override fun reload() {
        shutdown()
        init()
    }

    private const val PLAYER_ACTIVITY_HEARTBEAT_TICKS = 6_000L
}
