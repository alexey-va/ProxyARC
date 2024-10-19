package ru.arc.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import ru.arc.CommonCore;
import ru.arc.config.Config;
import ru.arc.config.ConfigManager;
import ru.arc.velocity.Velocity;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class ChatListener {

    final CommonCore commonCore;
    final ProxyServer proxyServer;
    final Config jippityConfig;
    final Config mainConfig = ConfigManager.of(Velocity.dataFolder, "config.yml");

    @Subscribe(async = true)
    public void onChatMessage(PlayerChatEvent event) {
        aiProcess(event);
        chatProcess(event);
    }

    private void chatProcess(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) return;
        if (!event.getMessage().startsWith("!")) return;
        String username = event.getPlayer().getUsername();
        String ip = event.getPlayer().getRemoteAddress().getAddress().getHostAddress();
        UUID uuid = event.getPlayer().getUniqueId();
        log.info("Player {} with IP {} sent message: {}", username, ip, event.getMessage());

        if (commonCore.getLiteBansHook() != null && commonCore.getLiteBansHook().isMuted(uuid, ip)) return;

        String message = event.getMessage().substring(1);
        Player player = event.getPlayer();
        Long firstJoinTime = commonCore.getFirstJoinData().getFirstJoinTime(player.getUsername());
        long minPlayerTime = mainConfig.integer("discord.min-play-time-sec", 600) * 1000L;
        if (firstJoinTime == null || firstJoinTime + minPlayerTime > System.currentTimeMillis()) return;
        String pattern = mainConfig.string("discord.chat-pattern", "**%player_name%** » %message%");
        String chatMessage = pattern
                .replace("%player_name%", player.getUsername())
                .replace("%message%", message);
        commonCore.getDiscordBot().sendChatMessage(chatMessage);

        String telegramPattern = mainConfig.string("telegram.chat-pattern", "\\*\\*%player_name%\\*\\* » %message%");
        chatMessage = telegramPattern
                .replace("%player_name%", player.getUsername())
                .replace("%message%", message);
        commonCore.getTelegramBot().sendChatMessage(chatMessage);
    }

    private void aiProcess(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) return;
        if (!event.getMessage().startsWith("!")) return;
        String message = event.getMessage().substring(1);
        Set<String> split = new HashSet<>();
        Collections.addAll(split, message.split(" "));
        boolean isJippity = split.contains(jippityConfig.string("giga-chat.detect-string", "ии"));
        commonCore.getChatHistory().add(event.getPlayer().getUsername(), message, isJippity ? "jippity" : "");
        if (isJippity) {
            commonCore.getJippityConversation()
                    .sendOpenaiApiRequest(
                            jippityConfig.string("giga-chat.send-string", "[%name%] %message%")
                                    .replace("%name%", event.getPlayer().getUsername())
                                    .replace("%message%", message))
                    .thenAcceptAsync(resp -> {
                        Component component = commonCore.getJippityConversation().toAiMessageComponent(resp);
                        proxyServer.sendMessage(component);
                        commonCore.getDiscordBot().sendChatMessage("**" + jippityConfig.string("giga-chat.name", "ГигаЧат") + "** » " + resp);
                    });
        }
    }

}
