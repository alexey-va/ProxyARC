package ru.arc.staff

import com.velocitypowered.api.proxy.ConsoleCommandSource
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.command.CommandExecuteEvent
import org.slf4j.LoggerFactory
import ru.arc.channelsync.ChannelSyncConfig
import ru.arc.config.ProxyConfigs
import ru.arc.core.PluginModule
import ru.arc.redis.network.RedisReplayPolicy
import ru.arc.redis.network.ValidatedRedisTopic
import ru.arc.velocity.Velocity
import java.util.concurrent.CompletableFuture

object StaffBridgeModule : PluginModule {
    override val name = "StaffBridge"
    override val priority = 79
    @Volatile internal var runtime: StaffBridgeRuntime? = null
        private set
    @Volatile private var protectedChannels: Set<String> = emptySet()

    override fun init() {
        shutdown()
        try {
            val config = StaffBridgeConfig.load()
            protectedChannels = config.discordDestinations
            if (!config.enabled) return
            val publicChannels = ProxyConfigs.module("discord.yml").let { cfg ->
                cfg.keys("channels").map { cfg.string("channels.$it", "") }.toSet()
            } + ChannelSyncConfig.load().let { if (it.enabled) it.validatedMappings().map { mapping -> mapping.discordChannelId } else emptyList() }
            config.validate(publicChannels)
            val created = StaffBridgeRuntime(config)
            runtime = created
            created.start()
            log.info("Staff bridge ready discord={} telegramDm={} backendCount={} spyOrigins={}",
                config.guildId.isNotEmpty(), config.telegramCommandsEnabled, config.backends.size, config.allowedOrigins)
        } catch (error: Exception) {
            runtime?.close()
            runtime = null
            log.error("Staff bridge disabled cause={}", error.javaClass.simpleName)
        }
    }

    fun protectedDiscordChannel(id: String): Boolean = id in protectedChannels
    internal fun registerDiscordCommands(jda: net.dv8tion.jda.api.JDA) { runtime?.let { StaffDiscordListener.register(jda, it.config) } }
    override fun reload() = init()
    override fun shutdown() { runtime?.close(); runtime = null }
    private val log = LoggerFactory.getLogger(StaffBridgeModule::class.java)
}

internal class StaffBridgeRuntime(val config: StaffBridgeConfig) : AutoCloseable {
    val discord = StaffDiscordGateway(config) { Velocity.discordBot?.staffJda() }
    private val dispatcher = StaffCommandDispatcher(Velocity.requireProxyServer(), config)
    val commands = StaffCommandService(config, Velocity.requireDataFolder(),
        identity = { provider, id ->
            when (provider) {
                "discord" -> Velocity.discordBot?.findIdentityByDiscordUser(id)?.let { StaffIdentity(it.playerUuid, it.playerName) }
                "telegram" -> id.toLongOrNull()?.let { Velocity.telegramBot?.findIdentityByTelegramUser(it) }?.let { StaffIdentity(it.playerUuid, it.playerName) }
                else -> null
            }
        },
        authorize = { identity ->
            Velocity.luckpermsHook?.staffActor(identity, config.adminGroups, config.moderatorGroups)
                ?: CompletableFuture.completedFuture(null)
        },
        dispatch = dispatcher::execute,
        auditConsole = { actor, command -> console("proxy", actor.identity.name, command.line) },
    )
    private val topics = mutableListOf<AutoCloseable>()
    @Volatile private var active = true

    fun start() {
        discord.start()
        Velocity.discordBot?.staffJda()?.let { StaffDiscordListener.register(it, config) }
        Velocity.requireProxyServer().eventManager.register(Velocity.requirePlugin(), this)
        val redis = Velocity.redisManager ?: return
        if (!config.spiesEnabled) return
        topics += ValidatedRedisTopic.open(redis, "arc.spy.v1", StaffSpyCodecs.spy,
            originAllowed = { it in config.allowedOrigins },
            replay = RedisReplayPolicy(StaffSpyEvent::id, 60_000, 4096),
            onMessage = { event, origin ->
                if (active && StaffSpyCodecs.current(event.createdAt, System.currentTimeMillis())) {
                    if (event.type == "chat") {
                        val target = event.targetName?.let { " → $it" }.orEmpty()
                        discord.enqueue(config.chatSpyChannelId, "[$origin] ${event.senderName}$target: ${event.content}")
                    } else if (!StaffCommands.sensitive(event.content)) {
                        discord.enqueue(config.commandSpyChannelId, "[$origin] ${event.senderName}: ${event.content}")
                    }
                }
            })
        topics += ValidatedRedisTopic.open(redis, "arc.staff-console.v1", StaffSpyCodecs.console,
            originAllowed = { it in config.allowedOrigins },
            replay = RedisReplayPolicy(StaffConsoleEvent::id, 60_000, 4096),
            onMessage = { event, origin ->
                if (active && StaffSpyCodecs.current(event.createdAt, System.currentTimeMillis())) console(origin, event.actor, event.command)
            })
    }

    @Subscribe(order = PostOrder.LAST)
    fun onConsole(event: CommandExecuteEvent) {
        if (!event.result.isAllowed) return
        if (event.commandSource is ConsoleCommandSource) console("proxy", "CONSOLE", event.command)
        val player = event.commandSource as? com.velocitypowered.api.proxy.Player ?: return
        if (active && config.spiesEnabled && StaffCommands.validLine(event.command) && !StaffCommands.sensitive(event.command) &&
            Velocity.requireProxyServer().commandManager.hasCommand(event.command.substringBefore(' '))) {
            discord.enqueue(config.commandSpyChannelId, "[proxy] ${player.username}: /${event.command}")
        }
    }

    private fun console(origin: String, actor: String, command: String) {
        if (active && config.spiesEnabled && StaffCommands.validLine(command) && !StaffCommands.sensitive(command)) {
            discord.enqueue(config.consoleChannels[origin], "[$origin] $actor: /${command.removePrefix("/")}")
        }
    }

    override fun close() {
        active = false
        commands.close()
        topics.forEach { runCatching { it.close() } }
        topics.clear()
        Velocity.proxyServer?.eventManager?.unregisterListener(Velocity.requirePlugin(), this)
        discord.close()
        dispatcher.close()
    }
}
