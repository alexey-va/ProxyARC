package ru.arc.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import ru.arc.CommonCore;
import ru.arc.ai.GPTEntity;
import ru.arc.config.Config;
import ru.arc.config.ConfigManager;
import ru.arc.velocity.Velocity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ru.arc.ai.GPTEntity.ModerationResponse.BAD;

@Slf4j
@RequiredArgsConstructor
public class ChatListener {

    final CommonCore commonCore;
    final ProxyServer proxyServer;
    final Config jippityConfig;
    final Config mainConfig = ConfigManager.of(Velocity.dataFolder, "config.yml");
    final Config moderConfig = ConfigManager.of(Velocity.dataFolder, "moderation.yml");

    Map<UUID, ModerationStatus> warnings = new ConcurrentHashMap<>();

    record ModerationStatus(int warns, long lastWarn) {
    }

    @Subscribe(async = true)
    public void onChatMessage(PlayerChatEvent event) {
        trimWarnings();
        processModerateMessage(event);

        aiProcess(event);
        chatProcess(event);
    }

    private void trimWarnings() {
        long warnExpireTime = moderConfig.integer("moderation.warn-expire-time-sec", 3600) * 1000L;
        long currentTime = System.currentTimeMillis();
        warnings.entrySet().removeIf(entry -> currentTime - entry.getValue().lastWarn > warnExpireTime);
    }

    private void processModerateMessage(PlayerChatEvent event) {
        log.info("Processing message: {}", event.getMessage());
        if (!event.getResult().isAllowed()) {
            log.info("Message is not allowed");
            return;
        }

        if (!event.getMessage().startsWith("!")) {
            log.info("Message does not start with !");
            return;
        }
        if (event.getPlayer().hasPermission("arc.moderation.bypass")) {
            log.info("Player has bypass permission");
            return;
        }
        String message = event.getMessage().substring(1);

        boolean isModerationEnabled = moderConfig.bool("moderation.enabled", false);
        long minPlayerTimeMs = moderConfig.integer("moderation.min-play-time-sec", 600) * 1000L;
        Long firstJoinTime = commonCore.getFirstJoinData().getFirstJoinTime(event.getPlayer().getUsername());

        if (!isModerationEnabled) return;

        long playerTime = System.currentTimeMillis() - firstJoinTime;
        log.info("Player {} has first join time: {}. Playtime {}ms", event.getPlayer().getUsername(), firstJoinTime, playerTime);
        if (playerTime > minPlayerTimeMs) {
            log.info("Player {} has played for long enough, skipping moderation", event.getPlayer().getUsername());
            return;
        }

        var resp = commonCore.getModeratorGpt().getModerResponse(event.getPlayer().getUsername(), message).join();
        log.info("Moderation response: {}", resp);
        if (resp.isEmpty()) {
            log.error("Empty response from GPT {}", message);
            return;
        }
        if (resp.get().message() == BAD) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            int warnCount = warnings.compute(event.getPlayer().getUniqueId(), (uuid, status) -> {
                int warns = status == null ? 0 : status.warns();
                return new ModerationStatus(warns + 1, System.currentTimeMillis());
            }).warns();
            int maxWarns = moderConfig.integer("moderation.max-warns", 3);
            event.getPlayer().sendMessage(moderConfig.componentDef("messages.moderation-denied",
                    "<red>Ваше сообщение было заблокировано модератором <gray>(<warns>/<max_warns>)<red> по причине: <gray><reason>",
                    "<reason>", resp.get().comment(),
                    "<warns>", String.valueOf(warnCount),
                    "<max_warns>", String.valueOf(maxWarns)
            ));

            if (warnCount >= maxWarns) {
                event.getPlayer().disconnect(moderConfig.componentDef("messages.kick-message",
                        "<red>Вы были отключены от сервера по причине превышения количества предупреждений <gray>(<warns>/<max_warns>)",
                        "<warns>", String.valueOf(warnCount),
                        "<max_warns>", String.valueOf(maxWarns)));
                warnings.remove(event.getPlayer().getUniqueId());
            }
        }
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

        if (isJippity) {
            commonCore.getGlobalGpt()
                    .getResponse(event.getPlayer().getUsername(), message)
                    .thenAcceptAsync(resp -> {
                        if (resp.isEmpty()) {
                            log.error("Empty response from GPT {}", message);
                            return;
                        }
                        Component component = commonCore.getGlobalGpt().toAiMessageComponent(resp.get());
                        proxyServer.sendMessage(component);
                        commonCore.getDiscordBot().sendChatMessage("**" + jippityConfig.string("ai.global.name", "Чат") + "** » " + resp);
                    });
        } else {
            commonCore.getGlobalGpt().addPlayerMessage(event.getPlayer().getUsername(), message);
        }
    }

}
