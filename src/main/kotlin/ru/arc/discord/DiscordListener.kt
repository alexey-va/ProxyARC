package ru.arc.discord

import org.slf4j.LoggerFactory
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import ru.arc.velocity.Velocity
import ru.arc.Utils.mm
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs

class DiscordListener(
    private val chatChanel: TextChannel,
    private val generalChannel: TextChannel,
) : ListenerAdapter() {

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) {
            log.info("Bot message: {}", event.message.contentRaw)
            return
        }
        if (event.message.hasChannel() && event.message.channelId == chatChanel.id) {
            log.info("Message in chat channel: {}", event.message.contentRaw)
            val format = config.string("chat-format", "<blue>D <gray>%player_name% <dark_gray>» <white>%message%")
            val message = format
                .replace("%player_name%", event.author.effectiveName)
                .replace("%message%", event.message.contentRaw)
            Velocity.plugin!!.sendMessageToAll(mm(message))

            val telegramFormat = config.string("telegram-format", "**%player_name%** » %message%")
            val telegramMessage = telegramFormat
                .replace("%player_name%", event.author.effectiveName)
                .replace("%message%", event.message.contentRaw)
            Velocity.telegramBot?.sendChatMessage(telegramMessage)
        }
        if (event.message.hasChannel() && event.message.channelId == generalChannel.id) {
            log.info("Message in general channel: {}", event.message.contentRaw)
            val format = config.string("telegram-format", "%player_name% » %message%")
            val message = format
                .replace("%player_name%", event.author.effectiveName)
                .replace("%message%", event.message.contentRaw)
            Velocity.telegramBot?.sendGeneralMessage(message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordListener::class.java)
        private val config: Config get() = ProxyConfigs.module("discord.yml")
    }
}
