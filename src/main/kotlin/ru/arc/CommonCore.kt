package ru.arc

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import ru.arc.ai.Assistant
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.discord.DiscordBot
import ru.arc.hooks.LiteBansHook
import ru.arc.hooks.LuckpermsHook
import ru.arc.telegram.TelegramBot
import ru.arc.xserver.JoinMessages
import ru.arc.xserver.NetworkRegistry
import ru.arc.xserver.PlayerListAnnouncer
import ru.arc.xserver.RedisManager
import ru.arc.xserver.repos.RedisRepo
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class CommonCore {
    var redisManager: RedisManager? = null
        private set
    var networkRegistry: NetworkRegistry? = null
        private set
    var discordBot: DiscordBot? = null
        private set
    var telegramBot: TelegramBot? = null
        private set
    var firstJoinData: FirstJoinData? = null
        private set
    var playerListAnnouncer: PlayerListAnnouncer? = null
        private set
    var antibot: Antibot? = null
        private set

    val saveService: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    var saveTask: ScheduledFuture<*>? = null
        private set
    var chatAssistant: Assistant? = null
        private set

    @JvmField
    var luckpermsHook: LuckpermsHook? = null

    @JvmField
    var liteBansHook: LiteBansHook? = null

    @JvmField
    var joinMessagesRedisRepo: RedisRepo<JoinMessages>? = null

    var arc: Arc? = null
        private set

    fun init(folder: Path, arc: Arc) {
        this.arc = arc

        try {
            Logging.addLokiAppender(folder)
        } catch (e: Exception) {
            log.error("Error adding loki appender", e)
        }

        inst = this
        CommonCore.folder = folder
        config = ConfigManager.of(folder, "config.yml")
        serverName = config!!.string("server-name", "proxy")

        println("Initializing core")
        try {
            startDiscordBot()
        } catch (e: Exception) {
            log.error("Error initializing discord bot", e)
        }
        startRedis()
        setupFirstTimeData()
        setupSaveTask()
        setupPlayerListAnnouncer()
        try {
            luckpermsHook = LuckpermsHook()
        } catch (e: Exception) {
            log.error("Error while initializing LuckpermsHook", e)
        }

        try {
            liteBansHook = LiteBansHook()
        } catch (e: Exception) {
            log.error("Error while initializing LiteBansHook", e)
        }

        try {
            val telegramConfig = ConfigManager.of(CommonCore.folder!!, "telegram.yml")
            val enabled = telegramConfig.bool("enabled", false)
            if (enabled) {
                val telegramBotsApi = TelegramBotsApi(DefaultBotSession::class.java)
                telegramBot = TelegramBot()
                telegramBotsApi.registerBot(telegramBot)
                log.info("TelegramBot initialized")
            } else {
                log.info("TelegramBot is disabled in config")
            }
        } catch (e: Exception) {
            log.error("Error while initializing TelegramBot", e)
        }

        if (joinMessagesRedisRepo == null) {
            joinMessagesRedisRepo = RedisRepo.builder(JoinMessages::class.java)
                .id("join_messages")
                .loadAll(true)
                .updateChannel("arc.join_messages_update")
                .redisManager(redisManager!!)
                .storageKey("arc.join_messages")
                .saveInterval(200L)
                .build()
        }

        antibot = Antibot(folder, firstJoinData!!)
        chatAssistant = Assistant(ConfigManager.of(folder, "assistant.yml"), "chat")
    }

    private fun setupPlayerListAnnouncer() {
        playerListAnnouncer = PlayerListAnnouncer(
            ConfigManager.of(folder!!, "config.yml"),
            redisManager!!,
            "arc.proxy_player_list",
        )
    }

    fun setupSaveTask() {
        saveTask = saveService.scheduleAtFixedRate({ save() }, 60, 60, TimeUnit.SECONDS)
    }

    fun cancelSaveTask() {
        saveTask?.cancel(false)
    }

    @Synchronized
    fun save() {
        firstJoinData?.save()
    }

    private fun setupFirstTimeData() {
        firstJoinData = FirstJoinData()
        firstJoinData!!.load()
    }

    private fun startDiscordBot() {
        discordBot = DiscordBot()
    }

    private fun startRedis() {
        val redisConfig = ConfigManager.of(folder!!, "config.yml")
        val host = redisConfig.string("redis.host", "localhost")
        val port = redisConfig.integer("redis.port", 6379)
        val username = redisConfig.string("redis.username", "default")
        val password = redisConfig.string("redis.password", "")

        redisManager = RedisManager(host, port, username, password)
        networkRegistry = NetworkRegistry(redisManager!!)
        networkRegistry!!.init()
    }

    companion object {
        private val log = LoggerFactory.getLogger(CommonCore::class.java)

        @JvmField
        var folder: Path? = null

        @JvmField
        var inst: CommonCore? = null

        @JvmField
        var config: Config? = null

        @JvmField
        var serverName: String = "proxy"
    }
}
