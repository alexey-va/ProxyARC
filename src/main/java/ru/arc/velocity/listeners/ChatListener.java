package ru.arc.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.grizzly.utils.EchoFilter;
import ru.arc.CommonCore;
import ru.arc.Utils;
import ru.arc.config.Config;
import ru.arc.config.ConfigManager;
import ru.arc.velocity.Velocity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class ChatListener {

    final CommonCore commonCore;
    final ProxyServer proxyServer;
    final Config jippityConfig;
    final Config mainConfig = ConfigManager.of(Velocity.dataFolder, "config.yml");
    final Config assistantConfig = ConfigManager.of(Velocity.dataFolder, "assistant.yml");

    Map<UUID, ModerationStatus> warnings = new ConcurrentHashMap<>();

    record ModerationStatus(int warns, long lastWarn) {
    }

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
        //log.info("Player {} with IP {} sent message: {}", username, ip, event.getMessage());

        if (commonCore.getLiteBansHook() != null && commonCore.getLiteBansHook().isMuted(uuid, ip)) return;

        String message = event.getMessage().substring(1);
        Player player = event.getPlayer();
        Long firstJoinTime = commonCore.getFirstJoinData().getFirstJoinTime(player.getUsername());
        long minPlayerTime = mainConfig.integer("discord.min-play-time-sec", 600) * 1000L;
        if (firstJoinTime == null || firstJoinTime + minPlayerTime > System.currentTimeMillis()) return;
        CompletableFuture.runAsync(() -> {
            String pattern = mainConfig.string("discord.chat-pattern", "**%player_name%** » %message%");
            String chatMessage = pattern.replace("%player_name%", username).replace("%message%", message);
            commonCore.getDiscordBot().sendChatMessage(chatMessage);

            String telegramPattern = mainConfig.string("telegram.chat-pattern", "\\*\\*%player_name%\\*\\* » %message%");
            chatMessage = telegramPattern.replace("%player_name%", username).replace("%message%", message);
            commonCore.getTelegramBot().sendChatMessage(chatMessage);
        });
    }

    private void aiProcess(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) return;
        if (!event.getMessage().startsWith("!")) return;
        String message = event.getMessage().substring(1);
        String playerName = event.getPlayer().getUsername();

        commonCore.getChatAssistant().addChatMessage(message, playerName);
        commonCore.getChatAssistant().tryEnqueue().thenAccept((response) -> {
            try {
                var prefix = assistantConfig.string("chat-prefix", "<gold>Бот <gray>» </gray><white>");
                log.info("Chat assistant response: {}", response);
                if (response.isEmpty()) {
                    log.error("Empty response from chat assistant");
                    return;
                }
                String reply = response.get();
                String[] chatMessages = reply.replace("\n\n", "\n").split("\n");
                int delay = 0;
                for (String chatMessage : chatMessages) {
                    if (chatMessage.equalsIgnoreCase("пропускаю")) continue;
                    var component = Utils.mm(prefix + chatMessage.trim());
                    proxyServer.getScheduler()
                            .buildTask(Velocity.plugin, () ->
                                    proxyServer.getAllPlayers().stream()
                                            .peek(p -> log.info("Sending AI message to player {}", p.getUsername()))
                                            .forEach(p -> p.sendMessage(component)))
                            .delay(delay, TimeUnit.SECONDS).schedule();
                    delay += 4;
                }
            } catch (Exception e) {
                log.error("Error while processing chat assistant response", e);
            }
        });
    }

}
