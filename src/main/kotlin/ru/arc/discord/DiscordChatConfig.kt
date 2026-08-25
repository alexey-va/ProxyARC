package ru.arc.discord

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import java.nio.file.Path

internal class DiscordChatConfig(
    private val config: Config,
) {
    val minecraftFormat: String get() = config.string("formats.minecraft", "").trim()
    val minecraftReplyFormat: String get() =
        config.string(
            "formats.minecraft-reply",
            "<white></white> <dark_gray>| <gray>%player_name% <hover:show_text:'<gray>%reply_name%: %reply_preview%'><dark_gray>←</dark_gray></hover> <white>%message%",
        ).trim()
    val telegramFormat: String get() = config.string("formats.telegram", "").trim()

    fun validate() {
        validateFormat("formats.minecraft", minecraftFormat)
        validateFormat("formats.telegram", telegramFormat)
        validateReplyFormat(minecraftReplyFormat)
        runCatching {
            minecraftMessage("PlayerName", Component.text("message"))
            minecraftReplyMessage("PlayerName", "Other", "preview", Component.text("message"))
        }.getOrElse { error ->
            throw IllegalArgumentException("formats.minecraft must be valid MiniMessage", error)
        }
    }

    fun minecraftMessage(
        playerName: String,
        message: Component,
    ): Component =
        MiniMessage.miniMessage().deserialize(
            minecraftFormat
                .replace(PLAYER_NAME_TOKEN, PLAYER_NAME_TAG)
                .replace(MESSAGE_TOKEN, MESSAGE_TAG),
            Placeholder.unparsed("player_name", playerName),
            Placeholder.component("message", message),
        )

    fun minecraftReplyMessage(
        playerName: String,
        replyName: String,
        replyPreview: String,
        message: Component,
    ): Component =
        MiniMessage.miniMessage().deserialize(
            minecraftReplyFormat
                .replace(PLAYER_NAME_TOKEN, PLAYER_NAME_TAG)
                .replace(REPLY_NAME_TOKEN, REPLY_NAME_TAG)
                .replace(REPLY_PREVIEW_TOKEN, REPLY_PREVIEW_TAG)
                .replace(MESSAGE_TOKEN, MESSAGE_TAG),
            Placeholder.unparsed("player_name", playerName),
            Placeholder.unparsed("reply_name", replyName),
            Placeholder.unparsed("reply_preview", replyPreview),
            Placeholder.component("message", message),
        )

    fun telegramMessage(
        playerName: String,
        message: String,
    ): String =
        FORMAT_TOKEN.replace(telegramFormat) { match ->
            when (match.value) {
                PLAYER_NAME_TOKEN -> playerName
                else -> message
            }
        }

    private fun validateFormat(
        path: String,
        format: String,
    ) {
        require(format.isNotBlank()) { "$path must not be blank" }
        require(format.length <= MAX_FORMAT_LENGTH) { "$path must not exceed $MAX_FORMAT_LENGTH characters" }
        require('\n' !in format && '\r' !in format) { "$path must be a single line" }
        require(format.countToken(PLAYER_NAME_TOKEN) == 1) { "$path must contain %player_name% exactly once" }
        require(format.countToken(MESSAGE_TOKEN) == 1) { "$path must contain %message% exactly once" }
    }

    private fun validateReplyFormat(format: String) {
        require(format.isNotBlank()) { "formats.minecraft-reply must not be blank" }
        require(format.length <= MAX_FORMAT_LENGTH) { "formats.minecraft-reply must not exceed $MAX_FORMAT_LENGTH characters" }
        require('\n' !in format && '\r' !in format) { "formats.minecraft-reply must be a single line" }
        listOf(PLAYER_NAME_TOKEN, REPLY_NAME_TOKEN, REPLY_PREVIEW_TOKEN, MESSAGE_TOKEN).forEach { token ->
            require(format.countToken(token) == 1) { "formats.minecraft-reply must contain $token exactly once" }
        }
    }

    companion object {
        private const val PLAYER_NAME_TOKEN = "%player_name%"
        private const val MESSAGE_TOKEN = "%message%"
        private const val REPLY_NAME_TOKEN = "%reply_name%"
        private const val REPLY_PREVIEW_TOKEN = "%reply_preview%"
        private const val PLAYER_NAME_TAG = "<player_name>"
        private const val MESSAGE_TAG = "<message>"
        private const val REPLY_NAME_TAG = "<reply_name>"
        private const val REPLY_PREVIEW_TAG = "<reply_preview>"
        private const val MAX_FORMAT_LENGTH = 512
        private val FORMAT_TOKEN = Regex("%(?:player_name|message)%")

        fun load(): DiscordChatConfig =
            DiscordChatConfig(ProxyConfigs.module("discord-chat.yml"))

        fun load(dataRoot: Path): DiscordChatConfig =
            DiscordChatConfig(ProxyConfigs.module(dataRoot, "discord-chat.yml"))

        private fun String.countToken(token: String): Int = windowed(token.length).count { it == token }
    }
}
