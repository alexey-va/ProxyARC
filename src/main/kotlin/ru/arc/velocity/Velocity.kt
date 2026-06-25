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
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import ru.arc.Antibot
import ru.arc.AntibotModule
import ru.arc.Arc
import ru.arc.ai.Assistant
import ru.arc.ai.AssistantModule
import ru.arc.ai.tools.Tool
import ru.arc.ai.tools.Tools
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.ModuleRegistry
import ru.arc.core.Tasks
import ru.arc.core.VelocityArcRuntime
import ru.arc.core.modules.ConfigModule
import ru.arc.core.modules.FirstJoinModule
import ru.arc.core.modules.JoinMessagesModule
import ru.arc.core.modules.ListenersModule
import ru.arc.core.modules.LoggingModule
import ru.arc.core.modules.NetworkModule
import ru.arc.core.modules.PlayerListModule
import ru.arc.core.modules.ProxyTasksModule
import ru.arc.core.modules.RedisModule
import ru.arc.core.modules.SaveModule
import ru.arc.discord.DiscordBot
import ru.arc.discord.DiscordModule
import ru.arc.FirstJoinData
import ru.arc.hooks.HooksModule
import ru.arc.hooks.LiteBansHook
import ru.arc.hooks.LuckpermsHook
import ru.arc.telegram.TelegramBot
import ru.arc.telegram.TelegramModule
import ru.arc.xserver.JoinMessages
import ru.arc.xserver.NetworkRegistry
import ru.arc.xserver.PlayerListAnnouncer
import ru.arc.xserver.RedisManager
import ru.arc.xserver.repos.RedisRepo
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

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
    init {
        plugin = this
        proxyServer = server
        Velocity.logger = logger
        Velocity.dataFolder = dataFolder
    }

    @Subscribe
    fun onProxyInit(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) {
        logger!!.info("Initializing ProxyARC")
        VelocityArcRuntime.installScheduling(proxyServer!!, this)
        VelocityArcRuntime.installModuleLifecycleReporting(
            consoleLog = { line -> logger!!.info(stripMiniMessage(line)) },
            logError = { msg, t -> logger!!.error(msg, t) },
        )
        registerModules()
        ModuleRegistry.initAll()
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

    private fun registerModules() {
        ModuleRegistry.registerAll(
            // Core infrastructure (10-29)
            LoggingModule,
            ConfigModule,
            RedisModule,
            NetworkModule,
            // Hooks (30)
            HooksModule,
            // Persistence & cross-server (50-69)
            FirstJoinModule,
            SaveModule,
            PlayerListModule,
            JoinMessagesModule,
            // Integrations (70-89)
            DiscordModule,
            TelegramModule,
            AntibotModule,
            AssistantModule,
            // Runtime (90-99)
            ListenersModule,
            ProxyTasksModule,
        )
    }

    private fun registerCommands() {
        proxyServer!!.commandManager.register("proxyarc", ProxyARCCommand())
    }

    @Subscribe
    fun onProxyReload(@Suppress("UNUSED_PARAMETER") event: ProxyReloadEvent) {
        Velocity.firstJoinData?.save()
        ModuleRegistry.reloadAll()
        Assistant.assistants.forEach { it.reload() }
    }

    @Subscribe
    fun onProxyStop(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
        isShuttingDown.set(true)
        ModuleRegistry.shutdownAll()
        Tasks.scheduler.cancelAll()
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

        @JvmField
        var config: Config? = null

        @JvmField
        var serverName: String = "proxy"

        @JvmField
        var redisManager: RedisManager? = null

        @JvmField
        var networkRegistry: NetworkRegistry? = null

        @JvmField
        var discordBot: DiscordBot? = null

        @JvmField
        var telegramBot: TelegramBot? = null

        @JvmField
        var firstJoinData: FirstJoinData? = null

        @JvmField
        var playerListAnnouncer: PlayerListAnnouncer? = null

        @JvmField
        var antibot: Antibot? = null

        @JvmField
        var chatAssistant: Assistant? = null

        @JvmField
        var luckpermsHook: LuckpermsHook? = null

        @JvmField
        var liteBansHook: LiteBansHook? = null

        @JvmField
        var joinMessagesRedisRepo: RedisRepo<JoinMessages>? = null

        private fun stripMiniMessage(line: String): String = line.replace(Regex("</?[^>]+>"), "")
    }
}
