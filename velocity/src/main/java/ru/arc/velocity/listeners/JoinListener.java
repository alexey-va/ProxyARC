package ru.arc.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import ru.arc.CommonCore;
import ru.arc.config.Config;
import ru.arc.discord.DiscordBot;
import ru.arc.velocity.Velocity;
import ru.arc.xserver.JoinMessages;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static ru.arc.Utils.mm;

@Slf4j
public record JoinListener(CommonCore commonCore, ProxyServer proxyServer, Config config) {

    @Subscribe(async = true)
    public void onPlayerJoin(PostLoginEvent event) {
        if (Velocity.isShuttingDown.get()) return;
        proxyServer.getScheduler()
                .buildTask(Velocity.plugin, () -> joinMessage(event.getPlayer()))
                .delay(1, TimeUnit.SECONDS)
                .schedule();
        Optional<String> server = event.getPlayer().getCurrentServer().map(o -> o.getServerInfo().getName());
        commonCore.getPlayerListAnnouncer().addPlayer(event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), server.orElse(null));
    }

    @Subscribe(async = true)
    public void onPlayerLeave(DisconnectEvent event) {
        if (!event.getPlayer().hasPermission("arc.join-message.leave")) return;
        if (Velocity.isShuttingDown.get()) return;
        proxyServer.getScheduler()
                .buildTask(Velocity.plugin, () -> leaveMessage(event.getPlayer()))
                .delay(1, TimeUnit.SECONDS)
                .schedule();
        commonCore.getPlayerListAnnouncer().removePlayer(event.getPlayer().getUniqueId());
    }

    @Subscribe(async = true)
    public void onChangeServer(ServerConnectedEvent event) {
        String server = event.getServer().getServerInfo().getName();
        String username = event.getPlayer().getUsername();
        commonCore.getPlayerListAnnouncer().updatePlayer(event.getPlayer().getUniqueId(), username, server);
    }

    private void sendMessageToAll(Component component) {
        proxyServer.getAllPlayers().forEach(p -> p.sendMessage(component));
    }

    private void joinMessage(Player player) {
        if (Velocity.isShuttingDown.get()) return;
        try {
            if (!player.isActive()) return;
            boolean firstTime = commonCore.getFirstJoinData().firstTimeJoin(player.getUsername());
            if (firstTime) {
                commonCore.getFirstJoinData().markAsJoined(player.getUsername());
                if (!player.hasPermission("arc.join-message.first")) return;
                commonCore.getDiscordBot().sendJoinEmbed(player.getUsername(), DiscordBot.JoinType.FIRST_TIME, null);
                commonCore.getTelegramBot().sendJoinMessage(player.getUsername(), DiscordBot.JoinType.FIRST_TIME, null);
                String message = config.string("messages.first-join", "<gray>Игрок <green>%player_name% <gray>присоединился впервые!");
                message = message.replace("%player_name%", player.getUsername());
                message = config.string("messages.join-prefix", "<dark_green>❖ ") + message;
                sendMessageToAll(mm(message));
            } else {
                if (!player.hasPermission("arc.join-message.join")) return;
                String messageOverride = null;
                if (commonCore.getJoinMessagesRedisRepo() != null) {
                    JoinMessages join = commonCore.getJoinMessagesRedisRepo().getOrNull(player.getUsername()).join();
                    if (join != null && !join.getJoinMessages().isEmpty()) {
                        int idx = ThreadLocalRandom.current().nextInt(join.getJoinMessages().size());
                        int current = 0;
                        for (String joinMessage : join.getJoinMessages()) {
                            if (current == idx) {
                                messageOverride = joinMessage;
                                messageOverride = messageOverride.replace("%player_name%", player.getUsername());
                                break;
                            }
                            current++;
                        }
                    }
                }
                commonCore.getDiscordBot().sendJoinEmbed(player.getUsername(), DiscordBot.JoinType.JOIN, messageOverride);
                commonCore.getTelegramBot().sendJoinMessage(player.getUsername(), DiscordBot.JoinType.JOIN, messageOverride);
                if (messageOverride != null)
                    messageOverride = config.string("messages.join-prefix", "<dark_green>❖ ") + messageOverride;
                String message = config.string("messages.join", "<gray>Игрок <green>%player_name% <gray>присоединился!");
                message = message.replace("%player_name%", player.getUsername());
                message = config.string("messages.join-prefix", "<dark_green>❖ ") + message;
                sendMessageToAll(messageOverride != null ? mm(messageOverride) : mm(message));
            }
        } catch (Exception e) {
            log.error("Error while sending join message", e);
        }
    }

    private void leaveMessage(Player player) {
        if (Velocity.isShuttingDown.get()) return;
        //if (!player.isActive()) return;
        String messageOverride = null;
        if (commonCore.getJoinMessagesRedisRepo() != null) {
            JoinMessages join = commonCore.getJoinMessagesRedisRepo().getOrNull(player.getUsername()).join();
            if (join != null && !join.getLeaveMessages().isEmpty()) {
                int idx = ThreadLocalRandom.current().nextInt(join.getLeaveMessages().size());
                int current = 0;
                for (String leaveMessage : join.getLeaveMessages()) {
                    if (current == idx) {
                        messageOverride = leaveMessage;
                        messageOverride = messageOverride.replace("%player_name%", player.getUsername());
                        break;
                    }
                    current++;
                }
            }
        }
        commonCore.getDiscordBot().sendJoinEmbed(player.getUsername(), DiscordBot.JoinType.LEAVE, messageOverride);
        commonCore.getTelegramBot().sendJoinMessage(player.getUsername(), DiscordBot.JoinType.LEAVE, messageOverride);
        if (messageOverride != null)
            messageOverride = config.string("messages.leave-prefix", "<dark_red>❖ ") + messageOverride;
        String message = config.string("messages.leave", "gray>Игрок <red>%player_name% <gray>вышел!");
        message = message.replace("%player_name%", player.getUsername());
        message = config.string("messages.leave-prefix", "<dark_red>❖ ") + message;
        sendMessageToAll(messageOverride != null ? mm(messageOverride) : mm(message));
    }

}
