package ru.arc.core.modules

import ru.arc.config.ProxyConfigs
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.velocity.Velocity
import ru.arc.velocity.listeners.ChatListener
import ru.arc.velocity.listeners.JoinListener
import java.util.concurrent.atomic.AtomicInteger

// ==================== Priority 90-99: Runtime ====================

object ListenersModule : PluginModule {
    override val name = "Listeners"
    override val priority = 90

    override fun init() {
        val plugin = Velocity.plugin!!
        val server = Velocity.proxyServer!!
        server.eventManager.register(
            plugin,
            JoinListener(server, ProxyConfigs.module("join_config.yml")),
        )
        server.eventManager.register(
            plugin,
            ChatListener(server, ProxyConfigs.main()),
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
    private val counter = AtomicInteger(0)

    override fun init() {
        val plugin = Velocity.plugin!!
        playerListTask =
            repeating(0, 200) {
                Velocity.discordBot?.updatePlayerList(plugin.onlinePlayerNames())
            }

        redisPlayerListTask =
            repeating(0, 20) {
                Velocity.playerListAnnouncer?.announce()
                if (counter.incrementAndGet() % 120 == 0) {
                    val players = Velocity.proxyServer!!.allPlayers
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
    }

    override fun shutdown() {
        playerListTask?.cancel()
        redisPlayerListTask?.cancel()
        playerListTask = null
        redisPlayerListTask = null
        counter.set(0)
    }

    override fun reload() {
        shutdown()
        init()
    }
}
