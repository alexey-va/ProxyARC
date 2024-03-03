package ru.arc.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import ru.arc.CommonCore;
import ru.arc.Config;
import ru.arc.DiscordBot;
import ru.arc.Utils;

@RequiredArgsConstructor
public class JoinListener {

    public final CommonCore commonCore;
    public final ProxyServer proxyServer;
    public final Config config;


    @Subscribe(async = true)
    public void onPlayerJoin(PostLoginEvent event) {
        boolean firstTime = commonCore.getFirstJoinData().firstTimeJoin(event.getPlayer().getUsername());
        if (firstTime) {
            commonCore.getFirstJoinData().markAsJoined(event.getPlayer().getUsername());
            if (!event.getPlayer().hasPermission("arc.join-message.first")) return;
            commonCore.getBot().sendJoinEmbed(event.getPlayer().getUsername(), DiscordBot.JoinType.FIRST_TIME);
            sendMessageToAll(config.component(
                    "first-time-message", true, "%player_name%", event.getPlayer().getUsername())
            );
        } else {
            if (!event.getPlayer().hasPermission("arc.join-message.join")) return;
            commonCore.getBot().sendJoinEmbed(event.getPlayer().getUsername(), DiscordBot.JoinType.JOIN);
            sendMessageToAll(config.component(
                    "join-message", true, "%player_name%", event.getPlayer().getUsername())
            );
        }
    }

    @Subscribe(async = true)
    public void onPlayerLeave(DisconnectEvent event) {
        if (!event.getPlayer().hasPermission("arc.join-message.leave")) return;
        commonCore.getBot().sendJoinEmbed(event.getPlayer().getUsername(), DiscordBot.JoinType.LEAVE);
        sendMessageToAll(config.component(
                "leave-message", true, "%player_name%", event.getPlayer().getUsername())
        );
    }

    private void sendMessageToAll(Component component) {
        proxyServer.getAllPlayers().forEach(p -> p.sendMessage(component));
    }

}
