package ru.arc.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import lombok.RequiredArgsConstructor;
import ru.arc.CommonCore;
import ru.arc.DiscordBot;

@RequiredArgsConstructor
public class JoinListener {

    public final CommonCore commonCore;


    @Subscribe(async = true)
    public void onPlayerJoin(PostLoginEvent event){
        boolean firstTime = commonCore.getFirstJoinData().firstTimeJoin(event.getPlayer().getUniqueId());
        if(!firstTime) {
            if (!event.getPlayer().hasPermission("arc.join-message.join")) return;
            commonCore.getBot().sendJoinEmbed(event.getPlayer().getUsername(), DiscordBot.JoinType.JOIN);
        } else{
            commonCore.getFirstJoinData().markAsJoined(event.getPlayer().getUniqueId());
            if (!event.getPlayer().hasPermission("arc.join-message.first")) return;
            commonCore.getBot().sendJoinEmbed(event.getPlayer().getUsername(), DiscordBot.JoinType.FIRST_TIME);
        }
    }

    @Subscribe(async = true)
    public void onPlayerLeave(DisconnectEvent event){
        if(!event.getPlayer().hasPermission("arc.join-message.leave")) return;
        commonCore.getBot().sendJoinEmbed(event.getPlayer().getUsername(), DiscordBot.JoinType.LEAVE);
    }

}
