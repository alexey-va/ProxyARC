package ru.arc.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.TaskStatus;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import ru.arc.Arc;
import ru.arc.CommonCore;
import ru.arc.config.ConfigManager;
import ru.arc.velocity.listeners.ChatListener;
import ru.arc.velocity.listeners.JoinListener;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Plugin(
        id = "proxyarc",
        name = "ProxyARC",
        version = "1.0"
)
@Getter
public class Velocity implements Arc {

    public static Velocity plugin;

    public static ProxyServer proxyServer;
    public static Logger logger;
    public static Path dataFolder;
    public static AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private ScheduledTask playerListTask;
    private ScheduledTask redisPlayerListTask;

    CommonCore commonCore;

    @Inject
    public Velocity(ProxyServer server, Logger logger, @DataDirectory Path dataFolder) {
        plugin = this;
        proxyServer = server;
        Velocity.logger = logger;
        Velocity.dataFolder = dataFolder;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        System.out.println("Initializing ProxyARC");
        commonCore = new CommonCore();
        commonCore.init(dataFolder, this);
        registerListeners();

        playerListTask = proxyServer.getScheduler().buildTask(this, () -> {
            commonCore.getDiscordBot().updatePlayerList(proxyServer.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .toList());
        }).repeat(10, TimeUnit.SECONDS).schedule();

        AtomicInteger counter = new AtomicInteger(0);
        redisPlayerListTask = proxyServer.getScheduler().buildTask(this, () -> {
            commonCore.getPlayerListAnnouncer().announce();
            if (counter.incrementAndGet() % 120 == 0) {
                Collection<Player> players = proxyServer.getAllPlayers();
                commonCore.getPlayerListAnnouncer().removeAllPlayers();
                players.forEach(p -> commonCore.getPlayerListAnnouncer().addPlayer(
                        p.getUniqueId(),
                        p.getUsername(),
                        p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null)
                ));
            }
        }).repeat(1, TimeUnit.SECONDS).schedule();

        registerCommands();

    }

    private void registerCommands() {
        proxyServer.getCommandManager().register("proxyarc", new ProxyARCCommand(commonCore));
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        commonCore.save();
    }

    @Subscribe
    public void onProxyStop(ProxyShutdownEvent event) {
        isShuttingDown.set(true);
        commonCore.save();
        cancelTasks();
    }

    public void cancelTasks() {
        if (playerListTask != null && playerListTask.status() == TaskStatus.SCHEDULED) playerListTask.cancel();
        if (redisPlayerListTask != null && redisPlayerListTask.status() == TaskStatus.SCHEDULED)
            redisPlayerListTask.cancel();
    }


    private void registerListeners() {
        proxyServer.getEventManager().register(this, new JoinListener(commonCore, proxyServer, ConfigManager.of(dataFolder, "join.yml")));
        proxyServer.getEventManager().register(this, new ChatListener(commonCore, proxyServer, ConfigManager.of(dataFolder, "config.yml")));
    }

    @Override
    public void sendMessageToAll(Component component) {
        proxyServer.getAllPlayers().forEach(p -> p.sendMessage(component));
    }

}
