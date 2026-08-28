package ru.arc.channelsync

import org.slf4j.LoggerFactory
import ru.arc.ops.DiscordMessageMutation
import ru.arc.ops.DiscordMessageMutationRequest
import ru.arc.ops.DiscordOpsGateway
import ru.arc.ops.TelegramMessageMutation
import ru.arc.ops.TelegramMessageMutationRequest
import ru.arc.ops.TelegramOpsGateway
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class DiscordSyncMessage(
    val channelId: String,
    val messageId: String,
    val sender: String,
    val text: String,
    val replyToMessageId: String? = null,
    val technical: DiscordSyncTechnicalText = DiscordSyncTechnicalText(),
)

data class TelegramSyncMessage(
    val chatId: String,
    val threadId: Int?,
    val messageId: Int,
    val sender: String,
    val text: String,
    val replyToMessageId: Int? = null,
    val entities: List<TelegramSyncEntity> = emptyList(),
)

class ChannelSyncService internal constructor(
    mappings: List<ChannelSyncMapping>,
    private val links: ChannelSyncLinkStore,
    private val discordProvider: () -> DiscordOpsGateway?,
    private val telegramProvider: () -> TelegramOpsGateway?,
    identityResolver: ChannelSyncIdentityResolver = ChannelSyncIdentityResolver(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val byDiscord = mappings.associateBy(ChannelSyncMapping::discordChannelId)
    private val byTelegram = mappings.associateBy { it.telegram }
    private val pendingTelegramSends = ConcurrentHashMap<TelegramSendFingerprint, AtomicInteger>()
    private val textCodec =
        ChannelSyncTextCodec(
            identities = identityResolver,
            telegramChannelMentionByDiscordId = { channelId -> byDiscord[channelId]?.telegramUsername },
            discordChannelMentionByTelegramUsername = { username ->
                mappings.firstOrNull { it.telegramUsername.equals(username, ignoreCase = true) }?.discordChannelId
            },
        )

    fun mappings(): List<ChannelSyncMapping> = byDiscord.values.sortedBy(ChannelSyncMapping::id)

    internal fun telegramUsernameForDiscordChannel(channelId: String): String? =
        byDiscord[channelId]?.telegramUsername

    internal fun discordChannelForTelegramUsername(username: String): String? =
        byDiscord.values.firstOrNull { it.telegramUsername.equals(username, ignoreCase = true) }?.discordChannelId

    fun relayDiscord(message: DiscordSyncMessage): Boolean {
        val mapping = byDiscord[message.channelId]?.takeIf { it.direction.fromDiscord() } ?: return false
        if (closed.get() || message.text.isBlank()) return true
        if (!links.reserveDiscord(mapping, message.messageId)) return true
        val gateway = telegramProvider()?.takeIf(TelegramOpsGateway::isReady)
        if (gateway == null) {
            links.abandonDiscord(mapping.id, message.messageId)
            log.warn("Channel sync Telegram destination is not ready mapping={}", mapping.id)
            return false
        }
        val replyTo =
            message.replyToMessageId?.let { replyId ->
                links.byDiscord(message.channelId, replyId)?.telegramMessageId
            }
        val translated = textCodec.discordToTelegram(message)
        val text = render(mapping.toTelegramFormat, telegramHtmlEscape(message.sender), translated.text)
        val plainText = render(mapping.toTelegramFormat, message.sender, translated.plainText)
        val fingerprint = TelegramSendFingerprint(mapping.telegram.chatId, mapping.telegram.threadId, plainText)
        markPendingTelegramSend(fingerprint)
        val future =
            try {
                gateway.mutateMessage(
                    TelegramMessageMutationRequest(
                        operation = TelegramMessageMutation.SEND,
                        chatId = mapping.telegram.chatId,
                        threadId = mapping.telegram.threadId,
                        text = text,
                        replyToMessageId = replyTo,
                        parseMode = translated.parseMode,
                    ),
                )
            } catch (error: Exception) {
                unmarkPendingTelegramSend(fingerprint)
                links.abandonDiscord(mapping.id, message.messageId)
                log.warn("Channel sync Discord->Telegram failed mapping={} error={}", mapping.id, error.javaClass.simpleName)
                return true
            }
        future.whenComplete { result, error ->
            try {
                val targetId = (result?.get("messageId") as? Number)?.toInt()
                if (error != null || targetId == null || targetId <= 0) {
                    links.abandonDiscord(mapping.id, message.messageId)
                    log.warn(
                        "Channel sync Discord->Telegram failed mapping={} error={}",
                        mapping.id,
                        error?.javaClass?.simpleName ?: "invalid-result",
                    )
                } else {
                    links.completeDiscord(mapping, message.messageId, targetId)
                }
            } finally {
                unmarkPendingTelegramSend(fingerprint)
            }
        }
        return true
    }

    fun relayTelegram(message: TelegramSyncMessage): Boolean {
        val mapping =
            byTelegram[ru.arc.telegram.TelegramDestination(message.chatId, message.threadId)]
                ?.takeIf { it.direction.fromTelegram() }
                ?: return false
        if (closed.get() || message.text.isBlank()) return true
        val fingerprint = TelegramSendFingerprint(message.chatId, message.threadId, message.text)
        if (pendingTelegramSends.containsKey(fingerprint)) return true
        if (!links.reserveTelegram(mapping, message.messageId)) return true
        val gateway = discordProvider()?.takeIf(DiscordOpsGateway::isReady)
        if (gateway == null) {
            links.abandonTelegram(mapping.id, message.chatId, message.threadId, message.messageId)
            log.warn("Channel sync Discord destination is not ready mapping={}", mapping.id)
            return false
        }
        val replyTo =
            message.replyToMessageId?.let { replyId ->
                links.byTelegram(message.chatId, message.threadId, replyId)?.discordMessageId
            }
        val translated = textCodec.telegramToDiscord(message)
        val future =
            try {
                gateway.mutateMessage(
                    DiscordMessageMutationRequest(
                        operation = DiscordMessageMutation.SEND,
                        channelId = mapping.discordChannelId,
                        content = render(mapping.toDiscordFormat, message.sender, translated.text),
                        replyToMessageId = replyTo,
                        allowedUserMentionIds = translated.allowedUserMentionIds,
                    ),
                )
            } catch (error: Exception) {
                links.abandonTelegram(mapping.id, message.chatId, message.threadId, message.messageId)
                log.warn("Channel sync Telegram->Discord failed mapping={} error={}", mapping.id, error.javaClass.simpleName)
                return true
            }
        future.whenComplete { result, error ->
            val targetId = result?.get("id")?.toString()?.takeIf(DISCORD_ID::matches)
            if (error != null || targetId == null) {
                links.abandonTelegram(mapping.id, message.chatId, message.threadId, message.messageId)
                log.warn(
                    "Channel sync Telegram->Discord failed mapping={} error={}",
                    mapping.id,
                    error?.javaClass?.simpleName ?: "invalid-result",
                )
            } else {
                links.completeTelegram(mapping, message.messageId, targetId)
            }
        }
        return true
    }

    fun editDiscord(message: DiscordSyncMessage): Boolean {
        val mapping =
            byDiscord[message.channelId]?.takeIf { it.direction.fromDiscord() && it.syncEdits }
                ?: return false
        val link = links.byDiscord(message.channelId, message.messageId) ?: return true
        if (link.source != SOURCE_DISCORD) return true
        val target = link.telegramMessageId ?: return true
        val gateway = telegramProvider()?.takeIf(TelegramOpsGateway::isReady)
        if (gateway == null) {
            log.warn("Channel sync Telegram edit destination is not ready mapping={}", mapping.id)
            return true
        }
        val translated = textCodec.discordToTelegram(message)
        gateway.mutateMessage(
            TelegramMessageMutationRequest(
                operation = TelegramMessageMutation.EDIT,
                chatId = mapping.telegram.chatId,
                messageId = target,
                text = render(mapping.toTelegramFormat, telegramHtmlEscape(message.sender), translated.text),
                parseMode = translated.parseMode,
            ),
        ).exceptionally { error ->
            log.warn("Channel sync Discord edit failed mapping={} error={}", mapping.id, error.javaClass.simpleName)
            null
        }
        return true
    }

    fun editTelegram(message: TelegramSyncMessage): Boolean {
        val destination = ru.arc.telegram.TelegramDestination(message.chatId, message.threadId)
        val mapping = byTelegram[destination]?.takeIf { it.direction.fromTelegram() && it.syncEdits } ?: return false
        val link = links.byTelegram(message.chatId, message.threadId, message.messageId) ?: return true
        if (link.source != SOURCE_TELEGRAM) return true
        val target = link.discordMessageId ?: return true
        val gateway = discordProvider()?.takeIf(DiscordOpsGateway::isReady)
        if (gateway == null) {
            log.warn("Channel sync Discord edit destination is not ready mapping={}", mapping.id)
            return true
        }
        val translated = textCodec.telegramToDiscord(message)
        gateway.mutateMessage(
            DiscordMessageMutationRequest(
                operation = DiscordMessageMutation.EDIT,
                channelId = mapping.discordChannelId,
                messageId = target,
                content = render(mapping.toDiscordFormat, message.sender, translated.text),
                allowedUserMentionIds = translated.allowedUserMentionIds,
            ),
        ).exceptionally { error ->
            log.warn("Channel sync Telegram edit failed mapping={} error={}", mapping.id, error.javaClass.simpleName)
            null
        }
        return true
    }

    fun deleteDiscord(
        channelId: String,
        messageId: String,
    ): Boolean {
        val mapping = byDiscord[channelId]?.takeIf { it.direction.fromDiscord() && it.syncDeletes } ?: return false
        val link = links.byDiscord(channelId, messageId) ?: return true
        if (link.source != SOURCE_DISCORD) return true
        val target = link.telegramMessageId ?: return true
        val gateway = telegramProvider()?.takeIf(TelegramOpsGateway::isReady)
        if (gateway == null) {
            log.warn("Channel sync Telegram delete destination is not ready mapping={}", mapping.id)
            return true
        }
        gateway.mutateMessage(
            TelegramMessageMutationRequest(
                operation = TelegramMessageMutation.DELETE,
                chatId = mapping.telegram.chatId,
                messageId = target,
            ),
        ).exceptionally { error ->
            log.warn("Channel sync Discord delete failed mapping={} error={}", mapping.id, error.javaClass.simpleName)
            null
        }
        return true
    }

    override fun close() {
        closed.set(true)
        pendingTelegramSends.clear()
    }

    private fun markPendingTelegramSend(fingerprint: TelegramSendFingerprint) {
        pendingTelegramSends.compute(fingerprint) { _, count ->
            (count ?: AtomicInteger()).also { it.incrementAndGet() }
        }
    }

    private fun unmarkPendingTelegramSend(fingerprint: TelegramSendFingerprint) {
        pendingTelegramSends.computeIfPresent(fingerprint) { _, count ->
            if (count.decrementAndGet() <= 0) null else count
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChannelSyncService::class.java)
        private val FORMAT_TOKEN = Regex("%(?:sender|message)%")
        private val DISCORD_ID = Regex("[0-9]{17,20}")
        private const val SOURCE_DISCORD = "discord"
        private const val SOURCE_TELEGRAM = "telegram"

        internal fun render(
            format: String,
            sender: String,
            message: String,
        ): String =
            FORMAT_TOKEN.replace(format) { match ->
                when (match.value) {
                    "%sender%" -> sender
                    else -> message
                }
            }

    }
}

private data class TelegramSendFingerprint(
    val chatId: String,
    val threadId: Int?,
    val text: String,
)
