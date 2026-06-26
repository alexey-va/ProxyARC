package ru.arc.discord

import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import ru.arc.ai.AssistantChatBridge
import ru.arc.ai.AssistantChatBridge.InboundSource
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import ru.arc.velocity.Velocity
import ru.arc.Utils.mm

class DiscordListener(
    private val chatChanel: TextChannel,
    private val generalChannel: TextChannel,
) : ListenerAdapter() {

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) {
            log.debug("Skip bot message: {}", event.message.contentRaw)
            return
        }
        if (event.message.hasChannel() && event.message.channelId == chatChanel.id) {
            val playerName = event.author.name
            val messageText = DiscordMessageText.formatForRelay(event.message)
            log.info("Discord chat from {}: raw=\"{}\" formatted=\"{}\"", playerName, event.message.contentRaw, messageText)

            if (messageText.isNotEmpty()) {
                relayToGame(playerName, messageText)
                relayToTelegram(playerName, messageText)
                relayToAssistant(playerName, messageText)
            }
        }
        if (event.message.hasChannel() && event.message.channelId == generalChannel.id) {
            val messageText = DiscordMessageText.formatForRelay(event.message)
            log.info("Discord general from {}: {}", event.author.effectiveName, messageText)
            val format = config.string("telegram-format", "%player_name% » %message%")
            val message = format
                .replace("%player_name%", event.author.effectiveName)
                .replace("%message%", messageText)
            Velocity.telegramBot?.sendGeneralMessage(message)
        }
    }

    private fun relayToGame(playerName: String, messageText: String) {
        val format = config.string("chat-format", "<blue>D <gray>%player_name% <dark_gray>» <white>%message%")
        val message = format
            .replace("%player_name%", playerName)
            .replace("%message%", messageText)
        Velocity.plugin!!.sendMessageToAll(mm(message))
    }

    private fun relayToTelegram(playerName: String, messageText: String) {
        val telegramFormat = config.string("telegram-format", "**%player_name%** » %message%")
        val telegramMessage = telegramFormat
            .replace("%player_name%", playerName)
            .replace("%message%", messageText)
        Velocity.telegramBot?.sendChatMessage(telegramMessage)
    }

    private fun relayToAssistant(playerName: String, messageText: String) {
        val proxy = Velocity.proxyServer ?: return
        AssistantChatBridge.processInbound(
            proxyServer = proxy,
            playerName = playerName,
            message = messageText,
            source = InboundSource.DISCORD,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordListener::class.java)
        private val config: Config get() = ProxyConfigs.module("discord.yml")
    }
}
