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
import ru.arc.activity.PlayerActivityTracker
import ru.arc.Arc
import ru.arc.ai.Assistant
import ru.arc.ai.AssistantModule
import ru.arc.ops.ProxyOpsHttpModule
import ru.arc.ai.tools.Tool
import ru.arc.ai.tools.Tools
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.ModuleRegistry
import ru.arc.core.Tasks
import ru.arc.core.VelocityArcRuntime
import ru.arc.core.modules.ProxyArcReload
import ru.arc.core.modules.ConfigModule
import ru.arc.core.modules.FirstJoinModule
import ru.arc.core.modules.JoinMessagesModule
import ru.arc.core.modules.ChatModeModule
import ru.arc.core.modules.ListenersModule
import ru.arc.core.modules.LoggingModule
import ru.arc.core.modules.MetricsModule
import ru.arc.core.modules.NetworkModule
import ru.arc.core.modules.PlayerListModule
import ru.arc.core.modules.PlayerActivityModule
import ru.arc.core.modules.ProxyTasksModule
import ru.arc.core.modules.RedisModule
import ru.arc.core.modules.RtpModule
import ru.arc.core.modules.SaveModule
import ru.arc.discord.DiscordBot
import ru.arc.discord.DiscordModule
import ru.arc.discord.VerifyCommand
import ru.arc.FirstJoinData
import ru.arc.hooks.HooksModule
import ru.arc.hooks.LiteBansHook
import ru.arc.hooks.LuckpermsHook
import ru.arc.telegram.TelegramBot
import ru.arc.telegram.TelegramModule
import ru.arc.xserver.NetworkRegistry
import ru.arc.xserver.PlayerListAnnouncer
import ru.arc.redis.RedisManager
import ru.arc.rtp.ProxyRtpConfig
import ru.arc.rtp.RtpCommand
import ru.arc.rtp.RtpRequestManager
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

@Plugin(
    id = "proxyarc",
    name = "ProxyARC",
    version = "1.0",
)
class Velocity @Inject constructor(
    private val server: ProxyServer,
    private val pluginLogger: Logger,
    @param:DataDirectory private val pluginDataFolder: Path,
) : Arc {
    init {
        installRuntimeReferences()
    }

    @Subscribe
    fun onProxyInit(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) {
        installRuntimeReferences()
        isShuttingDown.set(false)
        pluginLogger.info("Initializing ProxyARC")
        VelocityArcRuntime.installScheduling(server, this)
        proxyRestartService =
            ProxyRestartService(
                scheduler = Tasks.scheduler,
                playerCount = { server.playerCount },
                broadcast = { component -> sendMessageToAll(component) },
                shutdown = { reason -> server.shutdown(reason) },
                eventLog = { event -> pluginLogger.info("[ProxyRestart] {}", event) },
            )
        VelocityArcRuntime.installModuleLifecycleReporting(
            consoleLog = { line -> pluginLogger.info(stripMiniMessage(line)) },
            logError = { msg, t -> pluginLogger.error(msg, t) },
        )
        registerModules()
        ModuleRegistry.initAll()
        redisManager?.init()
        registerCommands()
        Tools.addTool(GetOnlinePlayers::class.java)
    }

    @JsonClassDescription("Get list of online players")
    data class GetOnlinePlayers(
        @param:JsonPropertyDescription("Stub field to differentiate tools")
        var stub: Boolean? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any? =
            requireProxyServer().allPlayers.map { it.username }
    }

    private fun registerModules() {
        ModuleRegistry.registerAll(
            // Core infrastructure (10-29)
            LoggingModule,
            ConfigModule,
            RedisModule,
            NetworkModule,
            MetricsModule,
            // Hooks (30)
            HooksModule,
            // Persistence & cross-server (50-69)
            FirstJoinModule,
            RtpModule,
            SaveModule,
            PlayerActivityModule,
            PlayerListModule,
            JoinMessagesModule,
            ChatModeModule,
            // Integrations (70-89)
            DiscordModule,
            TelegramModule,
            AntibotModule,
            AssistantModule,
            ProxyOpsHttpModule,
            // Runtime (90-99)
            ListenersModule,
            ProxyTasksModule,
        )
    }

    private fun registerCommands() {
        val commandManager = checkNotNull(proxyServer).commandManager
        val metadata =
            commandManager
                .metaBuilder("proxyarc")
                .plugin(this)
                .build()
        commandManager.register(metadata, ProxyARCCommand())

        val verifyMetadata =
            commandManager
                .metaBuilder("verify")
                .plugin(this)
                .build()
        commandManager.register(verifyMetadata, VerifyCommand())

        rtpRequestManager?.let { manager ->
            val rtpMetadata =
                commandManager
                    .metaBuilder("rtp")
                    .plugin(this)
                    .build()
            commandManager.register(rtpMetadata, RtpCommand(manager, ProxyRtpConfig()))
        }
    }

    @Subscribe
    fun onProxyReload(@Suppress("UNUSED_PARAMETER") event: ProxyReloadEvent) {
        ProxyArcReload.configsAndAssistant()
    }

    @Subscribe
    fun onProxyStop(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
        isShuttingDown.set(true)
        proxyRestartService?.shutdownModule()
        proxyRestartService = null
        ModuleRegistry.shutdownAll()
        Tasks.reset()
        plugin = null
        proxyServer = null
        logger = null
        dataFolder = null
        config = null
    }

    override fun sendMessageToAll(component: Component) {
        server.allPlayers.forEach { it.sendMessage(component) }
    }

    override fun onlinePlayerNames(): Collection<String> =
        server.allPlayers.map { it.username }

    private fun installRuntimeReferences() {
        plugin = this
        proxyServer = server
        logger = pluginLogger
        dataFolder = pluginDataFolder
    }

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
        var rtpRequestManager: RtpRequestManager? = null

        @JvmField
        var discordBot: DiscordBot? = null

        @JvmField
        var telegramBot: TelegramBot? = null

        @JvmField
        var firstJoinData: FirstJoinData? = null

        @JvmField
        var playerListAnnouncer: PlayerListAnnouncer? = null

        @JvmField
        var playerActivityTracker: PlayerActivityTracker? = null

        @JvmField
        var proxyRestartService: ProxyRestartService? = null

        @JvmField
        var llmClient: ru.arc.ai.llm.OpenRouterLlmClient? = null

        @JvmField
        var antibot: Antibot? = null

        @JvmField
        var chatAssistant: Assistant? = null

        @JvmField
        var bugSurveyAssistant: Assistant? = null

        @JvmField
        var luckpermsHook: LuckpermsHook? = null

        @JvmField
        var liteBansHook: LiteBansHook? = null

        internal fun requirePlugin(): Velocity =
            checkNotNull(plugin) { "ProxyARC plugin instance is not initialized" }

        internal fun requireProxyServer(): ProxyServer =
            checkNotNull(proxyServer) { "ProxyARC server is not initialized" }

        internal fun requireDataFolder(): Path =
            checkNotNull(dataFolder) { "ProxyARC data folder is not initialized" }

        internal fun requireLogger(): Logger =
            checkNotNull(logger) { "ProxyARC logger is not initialized" }

        private fun stripMiniMessage(line: String): String = line.replace(Regex("</?[^>]+>"), "")
    }
}
