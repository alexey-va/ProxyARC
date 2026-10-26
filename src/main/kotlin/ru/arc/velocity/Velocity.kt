package ru.arc.velocity

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.command.CommandExecuteEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyReloadEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import ru.arc.activity.PlayerActivityTracker
import ru.arc.Arc
import ru.arc.ai.Assistant
import ru.arc.ai.AssistantModule
import ru.arc.ops.ProxyOpsHttpModule
import ru.ruscrafting.votes.velocity.ProxyVotesModule
import ru.arc.portal.PortalBridgeModule
import ru.arc.portal.PortalBridgeService
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
import ru.arc.core.modules.JoinMessageCatalogModule
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
import ru.arc.core.modules.DungeonPartyModule
import ru.arc.discord.DiscordBot
import ru.arc.discord.DiscordModule
import ru.arc.discord.VerifyCommand
import ru.arc.discord.VerificationPlatform
import ru.arc.FirstJoinData
import ru.arc.hooks.HooksModule
import ru.arc.hooks.LiteBansHook
import ru.arc.hooks.LuckpermsHook
import ru.arc.telegram.TelegramBot
import ru.arc.telegram.TelegramModule
import ru.arc.channelsync.ChannelSyncModule
import ru.arc.channelsync.ChannelSyncService
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
    version = "1.0.15",
)
class Velocity @Inject constructor(
    private val server: ProxyServer,
    private val pluginLogger: Logger,
    @param:DataDirectory private val pluginDataFolder: Path,
) : Arc {
    private var runtimeStarted = false
    private val registeredCommands = mutableListOf<CommandMeta>()

    @Subscribe
    fun onProxyInit(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) {
        check(!runtimeStarted) { "ProxyARC runtime is already started" }
        runtimeStarted = true
        installRuntimeReferences()
        isShuttingDown.set(false)
        pluginLogger.info("Initializing ProxyARC")
        try {
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
        } catch (failure: Throwable) {
            stopRuntime()?.let { if (it !== failure) failure.addSuppressed(it) }
            pluginLogger.error("ProxyARC startup failed; owned resources were retired", failure)
            throw failure
        }
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
            ru.arc.metrics.ProxyPlayerTelemetryModule,
            // Hooks (30)
            HooksModule,
            // Persistence & cross-server (50-69)
            FirstJoinModule,
            RtpModule,
            DungeonPartyModule,
            PlayerActivityModule,
            PlayerListModule,
            JoinMessageCatalogModule,
            JoinMessagesModule,
            ChatModeModule,
            // Integrations (70-89)
            DiscordModule,
            TelegramModule,
            ChannelSyncModule,
            ru.arc.staff.StaffBridgeModule,
            AssistantModule,
            ProxyVotesModule,
            ProxyOpsHttpModule,
            PortalBridgeModule,
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
        registeredCommands += metadata
        commandManager.register(metadata, ProxyARCCommand())

        val verifyMetadata =
            commandManager
                .metaBuilder("verify")
                .plugin(this)
                .build()
        registeredCommands += verifyMetadata
        commandManager.register(verifyMetadata, VerifyCommand())

        val discordMetadata =
            commandManager
                .metaBuilder("discord")
                .plugin(this)
                .build()
        registeredCommands += discordMetadata
        commandManager.register(discordMetadata, VerifyCommand(VerificationPlatform.DISCORD))

        val telegramMetadata =
            commandManager
                .metaBuilder("telegram")
                .plugin(this)
                .build()
        registeredCommands += telegramMetadata
        commandManager.register(telegramMetadata, VerifyCommand(VerificationPlatform.TELEGRAM))

        rtpRequestManager?.let { manager ->
            val rtpMetadata =
                commandManager
                    .metaBuilder("rtp")
                    .plugin(this)
                    .build()
            registeredCommands += rtpMetadata
            commandManager.register(rtpMetadata, RtpCommand(manager, ProxyRtpConfig()))
        }
    }

    @Subscribe
    fun onProxyReload(@Suppress("UNUSED_PARAMETER") event: ProxyReloadEvent) {
        ProxyArcReload.reloadSupported()
    }

    // Plugin-lifetime guard survives StaffBridge reload/disable while an old command event is queued.
    @Subscribe(order = PostOrder.LAST)
    fun onStaffCommand(event: CommandExecuteEvent) {
        ru.arc.staff.StaffCommandSource.guard(event)
    }

    @Subscribe
    fun onProxyStop(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
        stopRuntime()?.let { pluginLogger.error("ProxyARC shutdown did not complete cleanly", it) }
    }

    /** Idempotent cleanup, including startup failure. Code replacement still requires proxy restart. */
    private fun stopRuntime(): Throwable? {
        if (!runtimeStarted) return null
        runtimeStarted = false
        isShuttingDown.set(true)
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                if (failure == null) failure = problem else if (failure !== problem) failure!!.addSuppressed(problem)
            }
        }
        attempt { server.eventManager.unregisterListeners(this) }
        registeredCommands.forEach { metadata ->
            metadata.aliases.forEach { alias ->
                attempt {
                    if (server.commandManager.getCommandMeta(alias) === metadata) {
                        server.commandManager.unregister(alias)
                    }
                }
            }
        }
        registeredCommands.clear()
        attempt { proxyRestartService?.shutdownModule() }
        proxyRestartService = null
        attempt { ModuleRegistry.shutdownAllChecked() }
        attempt { Tasks.reset() }
        attempt { ru.arc.logging.ArcLogging.close() }
        plugin = null
        proxyServer = null
        logger = null
        dataFolder = null
        config = null
        return failure
    }

    override fun sendMessageToAll(component: Component) {
        sendMessageToPlayers(server.allPlayers, component)
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
        var channelSync: ChannelSyncService? = null

        @JvmField
        var portalBridge: PortalBridgeService? = null

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
        var chatAssistant: Assistant? = null

        @JvmField
        internal var eventsChatIsolation: ru.arc.events.ArcEventsChatIsolation? = null

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

        /** Sends a proxy-wide message through the ArcEvents audience boundary. */
        fun sendMessageToPlayers(
            players: Iterable<Player>,
            component: Component,
        ): Int {
            val recipients = eventsChatIsolation?.recipients(players) ?: players.toList()
            recipients.forEach { it.sendMessage(component) }
            return recipients.size
        }

        /** Delivers a player-directed message unless the player is in an isolated ArcEvents lease. */
        fun sendMessageTo(
            source: CommandSource,
            component: Component,
        ): Boolean {
            if (source is Player && eventsChatIsolation?.isIsolated(source) == true) return false
            source.sendMessage(component)
            return true
        }

        private fun stripMiniMessage(line: String): String = line.replace(Regex("</?[^>]+>"), "")
    }
}
