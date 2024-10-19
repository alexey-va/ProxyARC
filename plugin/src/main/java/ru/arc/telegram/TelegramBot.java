package ru.arc.telegram;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import ru.arc.CommonCore;
import ru.arc.config.Config;
import ru.arc.config.ConfigManager;
import ru.arc.discord.DiscordBot;

import static ru.arc.Utils.mm;
import static ru.arc.Utils.plain;

@Slf4j
public class TelegramBot extends TelegramLongPollingBot {

    static Config config = ConfigManager.of(CommonCore.folder, "telegram.yml");

    public TelegramBot() {
        super(config.string("token", "none"));
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.getMessage().getFrom().getIsBot()) return;
        if (update.getMessage() == null) return;
        if (update.getMessage().getChatId() == null) return;

        log.info("Telegram message: {} in {} (TID: {})", update.getMessage().getText(), update.getMessage().getChatId(), update.getMessage().getMessageThreadId());

        Integer threadId = update.getMessage().getMessageThreadId();
        String sender = update.getMessage().getFrom().getUserName();
        String message = update.getMessage().getText();
        long chatId = update.getMessage().getChatId();

        if (chatId != config.longValue("chat-id", 0)) return;
        if (threadId != null && threadId == config.integer("topics.chat", 0)) {
            propagateChatMessage(sender, message);
        }
        if (threadId != null && threadId == config.integer("topics.general", 0)) {
            String format = config.string("discord-format", "**%sender%** » %message%");
            message = format
                    .replace("%sender%", sender)
                    .replace("%message%", message);
            CommonCore.inst.getDiscordBot().sendGeneralMessage(message);
        }
    }

    private void propagateChatMessage(String sender, String message) {
        String discordFormat = config.string("discord-format", "**%sender%** » %message%");
        String discordMessage = discordFormat
                .replace("%sender%", sender)
                .replace("%message%", message);
        CommonCore.inst.getDiscordBot().sendChatMessage(discordMessage);

        String chatFormat = config.string("chat-format", "<blue>T <gray>%sender% <dark_gray>» <white>%message%");
        String chatMessage = chatFormat
                .replace("%sender%", sender)
                .replace("%message%", message);
        CommonCore.inst.getArc().sendMessageToAll(mm(chatMessage));
    }

    public void sendChatMessage(String message) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(config.longValue("chat-id", 0));
        sendMessage.setText(message);
        sendMessage.setMessageThreadId(config.integer("topics.chat", 0));
        try {
            execute(sendMessage);
        } catch (Exception e) {
            log.error("Failed to send message to telegram", e);
        }
    }

    public void sendGeneralMessage(String message) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(config.longValue("chat-id", 0));
        sendMessage.setText(message);
        sendMessage.setMessageThreadId(config.integer("topics.general", -1));
        try {
            execute(sendMessage);
        } catch (Exception e) {
            log.error("Failed to send message to telegram", e);
        }
    }

    @Override
    public String getBotUsername() {
        return "RusCrafting";
    }

    public void sendJoinMessage(String username, DiscordBot.JoinType joinType, String messageOverride) {
        switch (joinType) {
            case JOIN -> {
                String message = messageOverride != null ? messageOverride :
                        config.string("messages." + joinType.name().toLowerCase(), "Игрок %player_name% присоединился к серверу");
                message = message.replace("%player_name%", username);
                sendChatMessage(plain(message));
            }
            case FIRST_TIME -> {
                String message = messageOverride != null ? messageOverride :
                        config.string("messages." + joinType.name().toLowerCase(), "Игрок %player_name% впервые присоединился к серверу");
                message = message.replace("%player_name%", username);
                sendChatMessage(plain(message));
            }
            case LEAVE -> {
                String message = messageOverride != null ? messageOverride :
                        config.string("messages." + joinType.name().toLowerCase(), "Игрок %player_name% покинул сервер");
                message = message.replace("%player_name%", username);
                sendChatMessage(plain(message));
            }
        }

    }
}
