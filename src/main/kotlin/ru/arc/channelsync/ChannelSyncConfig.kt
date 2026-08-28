package ru.arc.channelsync

import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import ru.arc.telegram.TelegramChatIds
import ru.arc.telegram.TelegramDestination
import java.nio.file.Path

enum class ChannelSyncDirection {
    BOTH,
    DISCORD_TO_TELEGRAM,
    TELEGRAM_TO_DISCORD,
    ;

    fun fromDiscord(): Boolean = this == BOTH || this == DISCORD_TO_TELEGRAM

    fun fromTelegram(): Boolean = this == BOTH || this == TELEGRAM_TO_DISCORD
}

data class ChannelSyncMapping(
    val id: String,
    val discordChannelId: String,
    val telegram: TelegramDestination,
    val telegramUsername: String? = null,
    val direction: ChannelSyncDirection = ChannelSyncDirection.BOTH,
    val syncEdits: Boolean = true,
    val syncDeletes: Boolean = true,
    val toDiscordFormat: String = "**%sender%** » %message%",
    val toTelegramFormat: String = "%sender% » %message%",
) {
    fun validate() {
        require(ID.matches(id)) { "invalid channel sync mapping id: $id" }
        require(DISCORD_ID.matches(discordChannelId)) { "invalid Discord channel id for mapping $id" }
        require(TelegramChatIds.isValid(telegram.chatId)) { "invalid Telegram chat id for mapping $id" }
        require(telegram.threadId == null || telegram.threadId > 0) { "invalid Telegram thread id for mapping $id" }
        require(telegramUsername == null || TELEGRAM_USERNAME.matches(telegramUsername)) {
            "invalid Telegram username for mapping $id"
        }
        validateFormat("to-discord-format", toDiscordFormat)
        validateFormat("to-telegram-format", toTelegramFormat)
    }

    private fun validateFormat(
        name: String,
        value: String,
    ) {
        require(value.isNotBlank() && value.length <= MAX_FORMAT_LENGTH) { "$name is invalid for mapping $id" }
        require('\n' !in value && '\r' !in value) { "$name must be one line for mapping $id" }
        require(value.countToken("%sender%") == 1) { "$name must contain %sender% once for mapping $id" }
        require(value.countToken("%message%") == 1) { "$name must contain %message% once for mapping $id" }
    }

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9_-]{0,47}")
        private val DISCORD_ID = Regex("[0-9]{17,20}")
        private val TELEGRAM_USERNAME = Regex("[A-Za-z0-9_]{5,32}")
        private const val MAX_FORMAT_LENGTH = 512

        private fun String.countToken(token: String): Int = windowed(token.length).count { it == token }
    }
}

open class ChannelSyncConfig(
    private val config: Config,
) {
    open val enabled: Boolean
        get() = config.bool("enabled", false)

    open val mappings: List<ChannelSyncMapping>
        get() = parseMappings(config.list("mappings", emptyList<Map<String, Any?>>()))

    fun validatedMappings(): List<ChannelSyncMapping> {
        val values = mappings
        require(values.size <= MAX_MAPPINGS) { "channel sync supports at most $MAX_MAPPINGS mappings" }
        values.forEach(ChannelSyncMapping::validate)
        require(values.map(ChannelSyncMapping::id).toSet().size == values.size) { "duplicate channel sync mapping id" }
        require(values.map(ChannelSyncMapping::discordChannelId).toSet().size == values.size) {
            "a Discord channel may belong to only one sync mapping"
        }
        require(values.map { it.telegram }.toSet().size == values.size) {
            "a Telegram chat/topic may belong to only one sync mapping"
        }
        val telegramUsernames = values.mapNotNull { it.telegramUsername?.lowercase() }
        require(telegramUsernames.toSet().size == telegramUsernames.size) {
            "a Telegram username may belong to only one sync mapping"
        }
        return values
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMappings(values: List<Map<String, Any?>>): List<ChannelSyncMapping> =
        values.map { value ->
            val telegramMap = value["telegram"] as? Map<String, Any?> ?: emptyMap()
            ChannelSyncMapping(
                id = value.requiredString("id"),
                discordChannelId = value.requiredString("discord-channel-id"),
                telegram =
                    TelegramDestination(
                        chatId = telegramMap.requiredString("chat-id"),
                        threadId = telegramMap.positiveIntOrNull("thread-id"),
                    ),
                telegramUsername = telegramMap.optionalString("username"),
                direction = value.enum("direction", ChannelSyncDirection.BOTH),
                syncEdits = value.boolean("sync-edits", true),
                syncDeletes = value.boolean("sync-deletes", true),
                toDiscordFormat = value.string("to-discord-format", "**%sender%** » %message%"),
                toTelegramFormat = value.string("to-telegram-format", "%sender% » %message%"),
            )
        }

    companion object {
        private const val MAX_MAPPINGS = 64

        fun load(): ChannelSyncConfig = ChannelSyncConfig(ProxyConfigs.module("channel-sync.yml"))

        fun load(dataRoot: Path): ChannelSyncConfig = ChannelSyncConfig(ProxyConfigs.module(dataRoot, "channel-sync.yml"))

        private fun Map<String, Any?>.requiredString(key: String): String =
            this[key]?.toString()?.trim()?.takeIf(String::isNotEmpty) ?: error("$key is required")

        private fun Map<String, Any?>.optionalString(key: String): String? =
            this[key]?.toString()?.trim()?.removePrefix("@")?.takeIf(String::isNotEmpty)

        private fun Map<String, Any?>.string(
            key: String,
            default: String,
        ): String = this[key]?.toString() ?: default

        private fun Map<String, Any?>.boolean(
            key: String,
            default: Boolean,
        ): Boolean = this[key] as? Boolean ?: default

        private fun Map<String, Any?>.positiveIntOrNull(key: String): Int? =
            when (val value = this[key]) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }?.takeIf { it > 0 }

        private inline fun <reified T : Enum<T>> Map<String, Any?>.enum(
            key: String,
            default: T,
        ): T =
            this[key]?.toString()?.let { raw ->
                enumValueOf<T>(raw.trim().uppercase().replace('-', '_'))
            } ?: default
    }
}
