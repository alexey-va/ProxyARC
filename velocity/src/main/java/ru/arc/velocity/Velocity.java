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
import org.slf4j.Logger;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import ru.arc.CommonCore;
import ru.arc.Config;
import ru.arc.ConfigManager;
import ru.arc.velocity.listeners.JoinListener;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "proxyarc",
        name = "ProxyARC",
        version = "1.0"
)
public class Velocity {


    private final ProxyServer proxyServer;
    private final Logger logger;
    @Getter
    private final Path dataFolder;
    private ScheduledTask playerListTask;

    @Getter
    CommonCore commonCore;

    @Inject
    public Velocity(ProxyServer server, Logger logger, @DataDirectory Path dataFolder){
        this.proxyServer=server;
        this.logger = logger;
        this.dataFolder=dataFolder;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event){
        System.out.println("Initializing ProxyARC");
        commonCore = new CommonCore();
        commonCore.init(dataFolder);
        registerListeners();

        playerListTask = proxyServer.getScheduler().buildTask(this, () -> {
            commonCore.getBot().updatePlayerList(proxyServer.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .toList());
        }).repeat(10, TimeUnit.SECONDS).schedule();
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event){
        commonCore.save();
    }

    @Subscribe
    public void onProxyStop(ProxyShutdownEvent event){
        commonCore.save();
        cancelTasks();
    }

    public void cancelTasks(){
        if(playerListTask != null && playerListTask.status() == TaskStatus.SCHEDULED) playerListTask.cancel();
    }


    private void registerListeners(){
        Config joinConfig = ConfigManager.create(dataFolder, "join_config.yml", "join");
        proxyServer.getEventManager().register(this, new JoinListener(commonCore, proxyServer, joinConfig));
    }


}
