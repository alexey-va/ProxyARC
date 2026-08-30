package ru.arc.discord

import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.slf4j.LoggerFactory
import ru.arc.ai.routing.ingress.ChatIngress
import ru.arc.channelsync.ChannelSyncModule
import ru.arc.channelsync.DiscordSyncMessage
import ru.arc.channelsync.telegramHtmlEscape
import ru.arc.ops.TelegramParseMode
import ru.arc.portal.PortalChatChannel
import ru.arc.portal.PortalChatMessage
import ru.arc.portal.PortalChatSource
import ru.arc.velocity.Velocity

internal class DiscordChatService(
    private val session: DiscordSession,
    private val config: DiscordChatConfig?,
    private val codec: DiscordMessageCodec,
    private val cleaner: DiscordChatCleaner,
    private val identityResolver: DiscordChatIdentityResolver = DiscordChatIdentityResolver(),
) {
    fun onMessage(event: MessageReceivedEvent) {
        if (event.author.isBot || event.message.isWebhookMessage) return
        val snapshot = session.snapshot() ?: return
        val (genericMessage, technical) = codec.discordToChannelSync(event.message)
        val portalChannel =
            when (event.channel.id) {
                snapshot.channels.chat.id -> PortalChatChannel.GAME
                snapshot.channels.general.id -> PortalChatChannel.COMMUNITY
                else -> null
            }
        if (portalChannel != null && genericMessage.isNotBlank()) {
            Velocity.portalBridge?.publishChat(
                PortalChatMessage(
                    sourceEventId = "discord:${event.channel.id}:${event.messageId}",
                    source = PortalChatSource.DISCORD,
                    channel = portalChannel,
                    authorUuid = Velocity.discordBot?.findIdentityByDiscordUser(event.author.id)?.playerUuid,
                    authorName = inboundAuthor(event),
                    content = genericMessage,
                    createdAt = event.message.timeCreated.toInstant().toEpochMilli(),
                ),
            )
        }
        if (genericMessage.isNotBlank() &&
            Velocity.channelSync?.relayDiscord(
                DiscordSyncMessage(
                    channelId = event.channel.id,
                    messageId = event.messageId,
                    sender = inboundAuthor(event),
                    text = genericMessage,
                    replyToMessageId = event.message.messageReference?.messageId,
                    technical = technical,
                ),
            ) == true
        ) {
            return
        }
        when (event.channel.id) {
            snapshot.channels.chat.id -> relayChatInbound(event)
            snapshot.channels.general.id -> relayGeneralInbound(event)
        }
    }

    fun onMessageUpdate(event: MessageUpdateEvent) {
        if (event.author.isBot || event.message.isWebhookMessage) return
        val (text, technical) = codec.discordToChannelSync(event.message)
        if (text.isBlank()) return
        Velocity.channelSync?.editDiscord(
            DiscordSyncMessage(
                channelId = event.channel.id,
                messageId = event.messageId,
                sender = identityResolver.resolve(
                    discordUserId = event.author.id,
                    discordDisplayName = event.member?.effectiveName ?: event.author.effectiveName,
                ),
                text = text,
                replyToMessageId = event.message.messageReference?.messageId,
                technical = technical,
            ),
        )
    }

    fun onMessageDelete(event: MessageDeleteEvent) {
        Velocity.channelSync?.deleteDiscord(event.channel.id, event.messageId)
    }

    fun sendChatMessage(
        message: String,
        allowedUserMentionIds: Set<String> = emptySet(),
    ) {
        val snapshot = session.snapshot() ?: return
        sendBounded(
            snapshot.channels.chat,
            codec.minecraftToDiscord(message, snapshot.channels.chat.guild),
            allowedUserMentionIds,
        )
    }

    fun sendGeneralMessage(
        message: String,
        allowedUserMentionIds: Set<String> = emptySet(),
    ) {
        val snapshot = session.snapshot() ?: return
        sendBounded(
            snapshot.channels.general,
            codec.minecraftToDiscord(message, snapshot.channels.general.guild),
            allowedUserMentionIds,
        )
    }

    fun clearChat(channelId: String) {
        if (session.isReady()) cleaner.start(channelId)
    }

    fun stopClearTask(channelId: String) {
        cleaner.stop(channelId)
    }

    private fun relayChatInbound(event: MessageReceivedEvent) {
        val configured = config ?: return
        val author = inboundAuthor(event)
        val messageText = codec.discordToMinecraft(event.message)
        if (messageText.isBlank()) return
        log.info("Discord chat relay from user={} chars={}", event.author.id, messageText.length)

        val referenced = event.message.referencedMessage
        val component =
            if (referenced == null) {
                configured.minecraftMessage(author, codec.minecraftBody(messageText))
            } else {
                val replyAuthor =
                    identityResolver.resolve(
                        discordUserId = referenced.author.id,
                        discordDisplayName = referenced.member?.effectiveName ?: referenced.author.effectiveName,
                    )
                val preview =
                    DiscordTextSafety.plain(codec.discordToMinecraft(referenced), REPLY_PREVIEW_LENGTH)
                        .ifBlank { "вложение" }
                configured.minecraftReplyMessage(author, replyAuthor, preview, codec.minecraftBody(messageText))
            }
        Velocity.plugin?.sendMessageToAll(component)

        Velocity.telegramBot?.sendChatMessage(
            configured.telegramMessage(
                telegramHtmlEscape(author),
                translateForTelegram(event).text,
            ),
            TelegramParseMode.HTML,
        )

        val proxy = Velocity.proxyServer ?: return
        val botId = event.jda.selfUser.id
        ChatIngress.onDiscordInbound(
            proxyServer = proxy,
            playerName = author,
            messageText = messageText,
            replyToBot = referenced?.author?.id == botId,
            replyToPlayer =
                referenced?.author?.takeIf { it.id != botId }?.let { referencedAuthor ->
                    identityResolver.resolve(
                        discordUserId = referencedAuthor.id,
                        discordDisplayName = referencedAuthor.name,
                    )
                },
        )
    }

    private fun relayGeneralInbound(event: MessageReceivedEvent) {
        val configured = config ?: return
        val messageText = codec.discordToMinecraft(event.message)
        if (messageText.isBlank()) return
        val author = inboundAuthor(event)
        Velocity.telegramBot?.sendGeneralMessage(
            configured.telegramMessage(
                telegramHtmlEscape(author),
                translateForTelegram(event).text,
            ),
            TelegramParseMode.HTML,
        )
    }

    private fun translateForTelegram(event: MessageReceivedEvent) =
        codec.discordToChannelSync(event.message).let { (text, technical) ->
            ChannelSyncModule.textCodec().discordToTelegram(
                DiscordSyncMessage(
                    channelId = event.channel.id,
                    messageId = event.messageId,
                    sender = inboundAuthor(event),
                    text = text,
                    technical = technical,
                ),
            )
        }

    private fun inboundAuthor(event: MessageReceivedEvent): String =
        identityResolver.resolve(
            discordUserId = event.author.id,
            discordDisplayName = event.member?.effectiveName ?: event.author.effectiveName,
        )

    private fun sendBounded(
        channel: TextChannel,
        message: String,
        allowedUserMentionIds: Set<String>,
    ) {
        splitMessage(message).forEach { part ->
            channel.sendMessage(codec.messageData(part, allowedUserMentionIds)).queue(
                {},
                { error -> log.warn("Failed to send Discord bridge message: {}", error.javaClass.simpleName) },
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordChatService::class.java)
        private const val DISCORD_MESSAGE_LIMIT = 2_000
        private const val REPLY_PREVIEW_LENGTH = 80

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
