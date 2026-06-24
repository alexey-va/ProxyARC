package ru.arc.velocity

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyReloadEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import ru.arc.core.ScheduledTask as CoreScheduledTask
import ru.arc.core.Tasks
import ru.arc.core.repeating
import ru.arc.velocity.core.VelocityTaskScheduler
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import ru.arc.Arc
import ru.arc.CommonCore
import ru.arc.ai.Assistant
import ru.arc.ai.tools.Tool
import ru.arc.ai.tools.Tools
import ru.arc.config.ConfigManager
import ru.arc.velocity.listeners.ChatListener
import ru.arc.velocity.listeners.JoinListener
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Plugin(
    id = "proxyarc",
    name = "ProxyARC",
    version = "1.0",
)
class Velocity @Inject constructor(
    server: ProxyServer,
    logger: Logger,
    @DataDirectory dataFolder: Path,
) : Arc {
    private var playerListTask: CoreScheduledTask? = null
    private var redisPlayerListTask: CoreScheduledTask? = null

    lateinit var commonCore: CommonCore
        private set

    init {
        plugin = this
        proxyServer = server
        Velocity.logger = logger
        Velocity.dataFolder = dataFolder
    }

    @Subscribe
    fun onProxyInit(event: ProxyInitializeEvent) {
        println("Initializing ProxyARC")
        Tasks.scheduler = VelocityTaskScheduler(proxyServer!!, this)
        commonCore = CommonCore()
        commonCore.init(dataFolder!!, this)
        registerListeners()

        playerListTask = repeating(0, 200) {
            commonCore.discordBot!!.updatePlayerList(onlinePlayerNames())
        }

        val counter = AtomicInteger(0)
        redisPlayerListTask = repeating(0, 20) {
            commonCore.playerListAnnouncer!!.announce()
            if (counter.incrementAndGet() % 120 == 0) {
                val players = proxyServer!!.allPlayers
                commonCore.playerListAnnouncer!!.removeAllPlayers()
                players.forEach { player ->
                    commonCore.playerListAnnouncer!!.addPlayer(
                        player.uniqueId,
                        player.username,
                        player.currentServer.map { it.serverInfo.name }.orElse(""),
                    )
                }
            }
        }

        registerCommands()

        Tools.addTool(GetOnlinePlayers::class.java)
    }

    @JsonClassDescription("Get list of online players")
    data class GetOnlinePlayers(
        @JsonPropertyDescription("Stub field to differentiate tools")
        var stub: Boolean? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any? =
            proxyServer!!.allPlayers.map { it.username }
    }

    private fun registerCommands() {
        proxyServer!!.commandManager.register("proxyarc", ProxyARCCommand(commonCore))
    }

    @Subscribe
    fun onProxyReload(event: ProxyReloadEvent) {
        commonCore.save()
    }

    @Subscribe
    fun onProxyStop(event: ProxyShutdownEvent) {
        isShuttingDown.set(true)
        commonCore.save()
        cancelTasks()
    }

    fun cancelTasks() {
        playerListTask?.cancel()
        redisPlayerListTask?.cancel()
        Tasks.scheduler.cancelAll()
    }

    private fun registerListeners() {
        proxyServer!!.eventManager.register(
            this,
            JoinListener(commonCore, proxyServer!!, ConfigManager.of(dataFolder!!, "join_config.yml")),
        )
        proxyServer!!.eventManager.register(
            this,
            ChatListener(commonCore, proxyServer!!, ConfigManager.of(dataFolder!!, "config.yml")),
        )
    }

    override fun sendMessageToAll(component: Component) {
        proxyServer!!.allPlayers.forEach { it.sendMessage(component) }
    }

    override fun onlinePlayerNames(): Collection<String> =
        proxyServer!!.allPlayers.map { it.username }

    companion object {
        @JvmField
        var plugin: Velocity? = null

        @JvmField
        var proxyServer: ProxyServer? = null

        @JvmField
        var logger: Logger? = null

        @JvmField
        var dataFolder: Path? = null

        @JvmField
        val isShuttingDown = AtomicBoolean(false)
    }
}
