package ru.arc;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.arc.ai.Assistant;
import ru.arc.config.Config;
import ru.arc.config.ConfigManager;
import ru.arc.discord.DiscordBot;
import ru.arc.hooks.LiteBansHook;
import ru.arc.hooks.LuckpermsHook;
import ru.arc.telegram.TelegramBot;
import ru.arc.xserver.JoinMessages;
import ru.arc.xserver.NetworkRegistry;
import ru.arc.xserver.PlayerListAnnouncer;
import ru.arc.xserver.RedisManager;
import ru.arc.xserver.repos.RedisRepo;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Getter
public class CommonCore {

    RedisManager redisManager;
    NetworkRegistry networkRegistry;
    DiscordBot discordBot;
    TelegramBot telegramBot;
    FirstJoinData firstJoinData;
    PlayerListAnnouncer playerListAnnouncer;
    Antibot antibot;

    ScheduledExecutorService saveService = Executors.newScheduledThreadPool(1);
    ScheduledFuture saveTask;
    Assistant chatAssistant;

    public LuckpermsHook luckpermsHook;
    public LiteBansHook liteBansHook;
    public RedisRepo<JoinMessages> joinMessagesRedisRepo;

    Arc arc;

    public static Path folder;
    public static CommonCore inst;
    public static Config config;
    public static String serverName = "proxy";

    public void init(Path folder, Arc arc) {
        this.arc = arc;

        try {
            Logging.addLokiAppender(folder);
        } catch (Exception e) {
            log.error("Error adding loki appender", e);
        }

        inst = this;
        CommonCore.folder = folder;
        config = ConfigManager.of(folder, "config.yml");
        serverName = config.string("server-name", "proxy");

        System.out.println("Initializing core");
        try {
            startDiscordBot();
        } catch (Exception e) {
            log.error("Error initializing discord bot", e);
        }
        startRedis();
        setupFirstTimeData();
        setupSaveTask();
        setupPlayerListAnnouncer();
        try {
            luckpermsHook = new LuckpermsHook();
        } catch (Exception e) {
            log.error("Error while initializing LuckpermsHook", e);
        }

        try {
            liteBansHook = new LiteBansHook();
        } catch (Exception e) {
            log.error("Error while initializing LiteBansHook", e);
        }

        try {
            Config config = ConfigManager.of(CommonCore.folder, "telegram.yml");
            boolean enabled = config.bool("enabled", false);
            if (enabled) {
                TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
                telegramBot = new TelegramBot();
                telegramBotsApi.registerBot(telegramBot);
                log.info("TelegramBot initialized");
            } else {
                log.info("TelegramBot is disabled in config");
            }
        } catch (Exception e) {
            log.error("Error while initializing TelegramBot", e);
        }

        if (joinMessagesRedisRepo == null) {
            joinMessagesRedisRepo = RedisRepo.builder(JoinMessages.class)
                    .id("join_messages")
                    .loadAll(true)
                    .updateChannel("arc.join_messages_update")
                    .redisManager(redisManager)
                    .storageKey("arc.join_messages")
                    .saveInterval(200L)
                    .build();
        }

        antibot = new Antibot(folder, firstJoinData);
        chatAssistant = new Assistant(ConfigManager.of(folder, "assistant.yml"), "chat");
    }

    private void setupPlayerListAnnouncer() {
        playerListAnnouncer = new PlayerListAnnouncer(ConfigManager.of(folder, "config.yml"), redisManager, "arc.proxy_player_list");
    }

    public void setupSaveTask() {
        saveTask = saveService.scheduleAtFixedRate(this::save, 60, 60, TimeUnit.SECONDS);
    }

    public void cancelSaveTask() {
        saveTask.cancel(false);
    }

    public synchronized void save() {
        if (firstJoinData != null) firstJoinData.save();
    }

    private void setupFirstTimeData() {
        firstJoinData = new FirstJoinData();
        firstJoinData.load();
    }

    private void startDiscordBot() {
        discordBot = new DiscordBot();
    }

    private void startRedis() {
        Config config = ConfigManager.of(folder, "config.yml");
        String host = config.string("redis.host", "localhost");
        int port = config.integer("redis.port", 6379);
        String username = config.string("redis.username", "default");
        String password = config.string("redis.password", "");

        redisManager = new RedisManager(host, port, username, password);
        networkRegistry = new NetworkRegistry(redisManager);
        networkRegistry.init();
    }

}
