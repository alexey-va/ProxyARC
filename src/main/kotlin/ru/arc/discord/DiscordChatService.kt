package ru.arc.discord

import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.slf4j.LoggerFactory
import ru.arc.ai.routing.ingress.ChatIngress
import ru.arc.config.Config
import ru.arc.velocity.Velocity

internal class DiscordChatService(
    private val session: DiscordSession,
    private val config: Config,
    private val codec: DiscordMessageCodec,
    private val cleaner: DiscordChatCleaner,
) {
    fun onMessage(event: MessageReceivedEvent) {
        if (event.author.isBot || event.isWebhookMessage) return
        val snapshot = session.snapshot() ?: return
        when (event.channel.id) {
            snapshot.channels.chat.id -> relayChatInbound(event)
            snapshot.channels.general.id -> relayGeneralInbound(event)
        }
    }

    fun sendChatMessage(message: String) {
        val snapshot = session.snapshot() ?: return
        sendBounded(snapshot.channels.chat, codec.minecraftToDiscord(message, snapshot.channels.chat.guild))
    }

    fun sendGeneralMessage(message: String) {
        val snapshot = session.snapshot() ?: return
        sendBounded(snapshot.channels.general, codec.minecraftToDiscord(message, snapshot.channels.general.guild))
    }

    fun clearChat(channelId: String) {
        if (session.isReady()) cleaner.start(channelId)
    }

    fun stopClearTask(channelId: String) {
        cleaner.stop(channelId)
    }

    private fun relayChatInbound(event: MessageReceivedEvent) {
        val author = event.member?.effectiveName ?: event.author.effectiveName
        val messageText = codec.discordToMinecraft(event.message)
        if (messageText.isBlank()) return
        log.info("Discord chat relay from user={} chars={}", event.author.id, messageText.length)

        val format =
            config.string(
                "chat-format",
                "<blue>D <gray><player_name> <dark_gray>» <white><message>",
            ).replace("%player_name%", "<player_name>")
                .replace("%message%", "<message>")
        val component =
            MiniMessage.miniMessage().deserialize(
                format,
                Placeholder.unparsed("player_name", author),
                Placeholder.component("message", codec.minecraftBody(messageText)),
            )
        Velocity.plugin?.sendMessageToAll(component)

        val telegramFormat = config.string("telegram-format", "**%player_name%** » %message%")
        Velocity.telegramBot?.sendChatMessage(
            telegramFormat.replace("%player_name%", author).replace("%message%", messageText),
        )

        val proxy = Velocity.proxyServer ?: return
        val referenced = event.message.referencedMessage
        val botId = event.jda.selfUser.id
        ChatIngress.onDiscordInbound(
            proxyServer = proxy,
            playerName = author,
            messageText = messageText,
            replyToBot = referenced?.author?.id == botId,
            replyToPlayer =
                referenced?.author?.name?.takeIf {
                    it.isNotEmpty() && referenced.author.id != botId
                },
        )
    }

    private fun relayGeneralInbound(event: MessageReceivedEvent) {
        val messageText = codec.discordToMinecraft(event.message)
        if (messageText.isBlank()) return
        val author = event.member?.effectiveName ?: event.author.effectiveName
        val format = config.string("telegram-format", "%player_name% » %message%")
        Velocity.telegramBot?.sendGeneralMessage(
            format.replace("%player_name%", author).replace("%message%", messageText),
        )
    }

    private fun sendBounded(
        channel: TextChannel,
        message: String,
    ) {
        splitMessage(message).forEach { part ->
            channel.sendMessage(codec.messageData(part)).queue(
                {},
                { error -> log.warn("Failed to send Discord bridge message: {}", error.javaClass.simpleName) },
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordChatService::class.java)
        private const val DISCORD_MESSAGE_LIMIT = 2_000

        internal fun splitMessage(message: String): List<String> {
            val remaining = ArrayDeque(message.trim().lines())
            val chunks = mutableListOf<String>()
            var current = StringBuilder()
            while (remaining.isNotEmpty()) {
                var line = remaining.removeFirst()
                while (line.length > DISCORD_MESSAGE_LIMIT) {
                    if (current.isNotEmpty()) {
                        chunks += current.toString()
                        current = StringBuilder()
                    }
                    var splitAt = DISCORD_MESSAGE_LIMIT
                    if (line[splitAt - 1].isHighSurrogate() && line[splitAt].isLowSurrogate()) {
                        splitAt--
                    }
                    chunks += line.take(splitAt)
                    line = line.drop(splitAt)
                }
                val separator = if (current.isEmpty()) "" else "\n"
                if (current.length + separator.length + line.length > DISCORD_MESSAGE_LIMIT) {
                    chunks += current.toString()
                    current = StringBuilder(line)
                } else {
                    current.append(separator).append(line)
                }
            }
            if (current.isNotEmpty()) chunks += current.toString()
            return chunks.filter(String::isNotBlank)
        }
    }
}
