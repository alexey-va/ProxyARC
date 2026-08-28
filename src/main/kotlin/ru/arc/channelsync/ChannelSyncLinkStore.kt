package ru.arc.channelsync

import com.google.gson.Gson
import ru.arc.Common
import ru.arc.persistence.AtomicFileStore
import ru.arc.telegram.TelegramChatIds
import java.nio.file.Path

internal data class ChannelSyncMessageLink(
    val mappingId: String,
    val source: String,
    val discordChannelId: String,
    val discordMessageId: String?,
    val telegramChatId: String,
    val telegramThreadId: Int?,
    val telegramMessageId: Int?,
    val createdAt: Long,
    val completedAt: Long?,
)

internal class ChannelSyncLinkStore(
    dataRoot: Path,
    private val clock: () -> Long = System::currentTimeMillis,
    private val gson: Gson = Common.prettyGson,
) {
    private val store =
        AtomicFileStore(
            root = dataRoot,
            relativePath = Path.of("data/channel-sync-links.json"),
            maxBytes = MAX_STORE_BYTES,
            encode = { state: StoredChannelSyncState -> gson.toJson(state).toByteArray() },
            decode = { bytes -> gson.fromJson(bytes.decodeToString(), StoredChannelSyncState::class.java) },
            validate = StoredChannelSyncState::validate,
        )
    private var state = store.loadOrDefault(::StoredChannelSyncState)

    @Synchronized
    fun reserveDiscord(
        mapping: ChannelSyncMapping,
        discordMessageId: String,
    ): Boolean {
        if (state.links.any { it.discordChannelId == mapping.discordChannelId && it.discordMessageId == discordMessageId }) {
            return false
        }
        val candidate = state.copyDeep()
        prune(candidate)
        candidate.links +=
            StoredChannelSyncLink(
                mappingId = mapping.id,
                source = SOURCE_DISCORD,
                discordChannelId = mapping.discordChannelId,
                discordMessageId = discordMessageId,
                telegramChatId = mapping.telegram.chatId,
                telegramThreadId = mapping.telegram.threadId,
                createdAt = clock(),
            )
        state = store.write(candidate)
        return true
    }

    @Synchronized
    fun reserveTelegram(
        mapping: ChannelSyncMapping,
        telegramMessageId: Int,
    ): Boolean {
        if (state.links.any {
                it.telegramChatId == mapping.telegram.chatId &&
                    it.telegramThreadId == mapping.telegram.threadId &&
                    it.telegramMessageId == telegramMessageId
            }
        ) {
            return false
        }
        val candidate = state.copyDeep()
        prune(candidate)
        candidate.links +=
            StoredChannelSyncLink(
                mappingId = mapping.id,
                source = SOURCE_TELEGRAM,
                discordChannelId = mapping.discordChannelId,
                telegramChatId = mapping.telegram.chatId,
                telegramThreadId = mapping.telegram.threadId,
                telegramMessageId = telegramMessageId,
                createdAt = clock(),
            )
        state = store.write(candidate)
        return true
    }

    @Synchronized
    fun completeDiscord(
        mapping: ChannelSyncMapping,
        discordMessageId: String,
        telegramMessageId: Int,
    ) {
        mutateLink(
            predicate = { it.mappingId == mapping.id && it.discordMessageId == discordMessageId },
            mutation = { link ->
                link.telegramMessageId = telegramMessageId
                link.completedAt = clock()
            },
        )
    }

    @Synchronized
    fun completeTelegram(
        mapping: ChannelSyncMapping,
        telegramMessageId: Int,
        discordMessageId: String,
    ) {
        mutateLink(
            predicate = {
                it.mappingId == mapping.id &&
                    it.telegramChatId == mapping.telegram.chatId &&
                    it.telegramThreadId == mapping.telegram.threadId &&
                    it.telegramMessageId == telegramMessageId
            },
            mutation = { link ->
                link.discordMessageId = discordMessageId
                link.completedAt = clock()
            },
        )
    }

    @Synchronized
    fun abandonDiscord(
        mappingId: String,
        discordMessageId: String,
    ) = removePending { it.mappingId == mappingId && it.discordMessageId == discordMessageId }

    @Synchronized
    fun abandonTelegram(
        mappingId: String,
        telegramChatId: String,
        telegramThreadId: Int?,
        telegramMessageId: Int,
    ) = removePending {
        it.mappingId == mappingId &&
            it.telegramChatId == telegramChatId &&
            it.telegramThreadId == telegramThreadId &&
            it.telegramMessageId == telegramMessageId
    }

    @Synchronized
    fun byDiscord(
        channelId: String,
        messageId: String,
    ): ChannelSyncMessageLink? =
        state.links.firstOrNull { it.discordChannelId == channelId && it.discordMessageId == messageId }?.toDomain()

    @Synchronized
    fun byTelegram(
        chatId: String,
        threadId: Int?,
        messageId: Int,
    ): ChannelSyncMessageLink? =
        state.links.firstOrNull {
            it.telegramChatId == chatId && it.telegramThreadId == threadId && it.telegramMessageId == messageId
        }?.toDomain()

    private fun mutateLink(
        predicate: (StoredChannelSyncLink) -> Boolean,
        mutation: (StoredChannelSyncLink) -> Unit,
    ) {
        val candidate = state.copyDeep()
        val link = candidate.links.firstOrNull(predicate) ?: return
        mutation(link)
        state = store.write(candidate)
    }

    private fun removePending(predicate: (StoredChannelSyncLink) -> Boolean) {
        val candidate = state.copyDeep()
        val removed = candidate.links.removeIf { predicate(it) && it.completedAt == null }
        if (removed) state = store.write(candidate)
    }

    private fun prune(candidate: StoredChannelSyncState) {
        while (candidate.links.size >= MAX_LINKS) {
            val oldest = candidate.links.minByOrNull(StoredChannelSyncLink::createdAt) ?: break
            candidate.links.remove(oldest)
        }
    }

    private fun StoredChannelSyncLink.toDomain(): ChannelSyncMessageLink =
        ChannelSyncMessageLink(
            mappingId = mappingId,
            source = source,
            discordChannelId = discordChannelId,
            discordMessageId = discordMessageId,
            telegramChatId = telegramChatId,
            telegramThreadId = telegramThreadId,
            telegramMessageId = telegramMessageId,
            createdAt = createdAt,
            completedAt = completedAt,
        )

    companion object {
        private const val MAX_LINKS = 5_000
        private const val MAX_STORE_BYTES = 2L * 1024L * 1024L
        private const val SOURCE_DISCORD = "discord"
        private const val SOURCE_TELEGRAM = "telegram"
    }
}

internal class StoredChannelSyncState {
    var schemaVersion: Int = 1
    var links: MutableList<StoredChannelSyncLink> = mutableListOf()

    fun validate() {
        require(schemaVersion == 1) { "unsupported channel sync schema $schemaVersion" }
        require(links.size <= 5_000) { "too many channel sync links" }
        links.forEach(StoredChannelSyncLink::validate)
        require(links.mapNotNull { link ->
            link.discordMessageId?.let { "${link.discordChannelId}:$it" }
        }.toSet().size == links.count { it.discordMessageId != null }) { "duplicate Discord message link" }
        require(links.mapNotNull { link ->
            link.telegramMessageId?.let { "${link.telegramChatId}:${link.telegramThreadId}:$it" }
        }.toSet().size == links.count { it.telegramMessageId != null }) { "duplicate Telegram message link" }
    }

    fun copyDeep(): StoredChannelSyncState =
        StoredChannelSyncState().also { copy ->
            copy.schemaVersion = schemaVersion
            copy.links = links.mapTo(mutableListOf(), StoredChannelSyncLink::copy)
        }
}

internal data class StoredChannelSyncLink(
    var mappingId: String = "",
    var source: String = "",
    var discordChannelId: String = "",
    var discordMessageId: String? = null,
    var telegramChatId: String = "",
    var telegramThreadId: Int? = null,
    var telegramMessageId: Int? = null,
    var createdAt: Long = 0,
    var completedAt: Long? = null,
) {
    fun validate() {
        require(mappingId.matches(Regex("[a-z0-9][a-z0-9_-]{0,47}"))) { "invalid mapping id" }
        require(source == "discord" || source == "telegram") { "invalid sync source" }
        require(discordChannelId.matches(Regex("[0-9]{17,20}"))) { "invalid Discord channel id" }
        require(discordMessageId == null || discordMessageId!!.matches(Regex("[0-9]{17,20}"))) {
            "invalid Discord message id"
        }
        require(TelegramChatIds.isValid(telegramChatId)) { "invalid Telegram chat id" }
        require(telegramThreadId == null || telegramThreadId!! > 0) { "invalid Telegram thread id" }
        require(telegramMessageId == null || telegramMessageId!! > 0) { "invalid Telegram message id" }
        require(createdAt >= 0 && (completedAt == null || completedAt!! >= createdAt)) { "invalid sync timestamps" }
        require(discordMessageId != null || telegramMessageId != null) { "sync link has no source message" }
    }
}
