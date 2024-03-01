package ru.arc;

import lombok.Getter;

import java.nio.file.Path;
import java.util.concurrent.*;

public class CommonCore {

    Config config;
    Config discordConfig;
    RedisManager redisManager;
    NetworkRegistry networkRegistry;
    @Getter
    DiscordBot bot;
    @Getter
    FirstJoinData firstJoinData;

    ScheduledExecutorService saveService = Executors.newScheduledThreadPool(1);
    ScheduledFuture saveTask;

    public void init(Path folder){
        System.out.println("Initializing core");
        setupConfigs(folder);
        startDiscordBot();
        startRedis();
        setupFirstTimeData(folder);
        setupSaveTask();
    }

    public void setupSaveTask(){
        saveTask = saveService.scheduleAtFixedRate(this::save, 60, 60, TimeUnit.SECONDS);
    }

    public void cancelSaveTask(){
        saveTask.cancel(false);
    }

    public synchronized void save(){
        firstJoinData.save();
    }

    private void setupFirstTimeData(Path folder){
        firstJoinData = new FirstJoinData(folder.resolve("first_time_join.json"));
        firstJoinData.load();
    }

    private void setupConfigs(Path folder){
        config = ConfigManager.create(folder, "config.yml", "config");
        discordConfig = ConfigManager.create(folder, "discord.yml", "discord");
    }

    private void startDiscordBot(){
        bot = new DiscordBot(discordConfig);
    }

    private void startRedis(){
        String host = config.string("redis.host", "localhost");
        int port = config.integer("redis.port", 6379);
        String username = config.string("redis.username", "default");
        String password = config.string("redis.password", "");

        redisManager = new RedisManager(host, port, username, password);
        networkRegistry = new NetworkRegistry(redisManager);
        networkRegistry.init();
    }

}
