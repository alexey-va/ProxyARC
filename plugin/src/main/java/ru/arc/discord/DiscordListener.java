package ru.arc.discord;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import ru.arc.CommonCore;
import ru.arc.config.Config;
import ru.arc.config.ConfigManager;

import static ru.arc.Utils.mm;

@Slf4j
@RequiredArgsConstructor
public class DiscordListener extends ListenerAdapter {

    static Config config = ConfigManager.of(CommonCore.folder, "discord.yml");
    TextChannel chatChanel;
    TextChannel generalChannel;

    public DiscordListener(TextChannel channel, TextChannel generalChannel) {
        this.chatChanel = channel;
        this.generalChannel = generalChannel;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            log.info("Bot message: {}", event.getMessage().getContentRaw());
            return;
        }
        if (event.getMessage().hasChannel() && event.getMessage().getChannelId().equals(chatChanel.getId())) {
            log.info("Message in chat channel: {}", event.getMessage().getContentRaw());
            String format = config.string("chat-format", "<blue>D <gray>%player_name% <dark_gray>» <white>%message%");
            String message = format
                    .replace("%player_name%", event.getAuthor().getEffectiveName())
                    .replace("%message%", event.getMessage().getContentRaw());
            CommonCore.inst.getArc().sendMessageToAll(mm(message));

            String telegramFormat = config.string("telegram-format", "**%player_name%** » %message%");
            String telegramMessage = telegramFormat
                    .replace("%player_name%", event.getAuthor().getEffectiveName())
                    .replace("%message%", event.getMessage().getContentRaw());
            CommonCore.inst.getTelegramBot().sendChatMessage(telegramMessage);
        }
        if (event.getMessage().hasChannel() && event.getMessage().getChannelId().equals(generalChannel.getId())) {
            log.info("Message in general channel: {}", event.getMessage().getContentRaw());
            String format = config.string("telegram-format", "%player_name% » %message%");
            String message = format
                    .replace("%player_name%", event.getAuthor().getEffectiveName())
                    .replace("%message%", event.getMessage().getContentRaw());
            CommonCore.inst.getTelegramBot().sendGeneralMessage(message);
        }

    }

}
