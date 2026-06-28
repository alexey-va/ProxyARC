package ru.arc.ai

import ru.arc.config.Config

object AssistantChatFormat {
    const val DEFAULT_DISPLAY_NAME = "Скорен"

    /** CMI `Locale_RU.yml` → `Chat.shoutPrefix`. */
    const val CMI_SHOUT_PREFIX = "&6Ⓖ &7"

    /**
     * CMI global `!` shout: `{shout}` + `Chat.yml` `GeneralFormat`.
     * `%luckperms_suffix%` → ItemsAdder rank icon in suffix slot.
     */
    const val CMI_GLOBAL_FORMAT = "%shout%%suffix% &7%name% &8» &f%message%"

    /** ItemsAdder `lzranks:supporter_icon` — bot marker in rank slot (was redstone_icon). */
    const val DEFAULT_SUFFIX = "&f${'\uE29D'}"

    const val DEFAULT_MAX_MESSAGE_LENGTH = 70
    const val DEFAULT_MULTI_MESSAGE_DELAY_MS = 1000L
    const val DEFAULT_CONTINUATION_WINDOW_SEC = 90
    const val DEFAULT_OBSERVE_FORMAT = "[%time% %delta%] %flags%%player% » %message%"
    const val MAX_REPLY_PARTS = 3

    fun displayName(config: Config): String =
        config.string("chat.display-name", DEFAULT_DISPLAY_NAME)

    fun shoutPrefix(config: Config): String =
        config.string("chat.shout-prefix", CMI_SHOUT_PREFIX)

    fun suffix(config: Config): String =
        config.string("chat.suffix", DEFAULT_SUFFIX)

    fun maxMessageLength(config: Config): Int =
        config.integer("chat.max-message-length", DEFAULT_MAX_MESSAGE_LENGTH).coerceIn(20, 256)

    fun multiMessageDelayMs(config: Config): Long =
        config.longValue("chat.multi-message-delay-ms", DEFAULT_MULTI_MESSAGE_DELAY_MS).coerceIn(200L, 5000L)

    fun continuationWindowSec(config: Config): Int =
        config.integer("chat.continuation-window-sec", DEFAULT_CONTINUATION_WINDOW_SEC).coerceIn(10, 600)

    fun observeFormat(config: Config): String =
        config.string("chat.observe-format", DEFAULT_OBSERVE_FORMAT)

    fun inGameMessage(
        config: Config,
        message: String,
    ): String {
        val format = config.string("chat.message-format", CMI_GLOBAL_FORMAT)
        return applyPlaceholders(format, config, message)
    }

    fun privateMessage(
        config: Config,
        message: String,
    ): String {
        val name = displayName(config)
        val template = config.string("chat.private-message-format", "&7[$name] &f%message%")
        return template.replace("%name%", name).replace("%message%", message)
    }

    internal fun applyPlaceholders(
        format: String,
        config: Config,
        message: String,
    ): String {
        val name = displayName(config)
        return format
            .replace("%shout%", shoutPrefix(config))
            .replace("{shout}", shoutPrefix(config))
            .replace("%suffix%", suffix(config))
            .replace("%luckperms_suffix%", suffix(config))
            .replace("%name%", name)
            .replace("{displayName}", name)
            .replace("%message%", message)
            .replace("{message}", message)
    }

    /** Одна строка для чата; обрезка по слову, без многоточия. */
    fun normalizeReply(
        config: Config,
        rawReply: String,
    ): String? = normalizeReplyDetail(config, rawReply).text

    fun normalizeReplyDetail(
        config: Config,
        rawReply: String,
    ): NormalizedChatReply {
        val parts = splitReplyParts(config, rawReply)
        if (parts.isEmpty()) {
            val trimmed = rawReply.trim()
            return when {
                trimmed.isEmpty() -> NormalizedChatReply(skipReason = "empty after trim")
                trimmed.equals("пропускаю", ignoreCase = true) ->
                    NormalizedChatReply(skipReason = "model said пропускаю")
                else -> NormalizedChatReply(skipReason = "empty after length clamp")
            }
        }
        return NormalizedChatReply(parts = parts)
    }

    /** Split on blank line; each block is one chat message (single line inside). */
    fun splitReplyParts(
        config: Config,
        rawReply: String,
    ): List<String> {
        val trimmed = rawReply.trim()
        if (trimmed.isEmpty() || trimmed.equals("пропускаю", ignoreCase = true)) {
            return emptyList()
        }
        val maxLen = maxMessageLength(config)
        return trimmed
            .split("\n\n")
            .map { block -> block.replace('\n', ' ').trim() }
            .filter { it.isNotEmpty() && !it.equals("пропускаю", ignoreCase = true) }
            .map { clampForChat(it, maxLen) }
            .filter { it.isNotEmpty() }
            .take(MAX_REPLY_PARTS)
    }

    internal fun clampForChat(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val cut = text.take(maxLen)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace > maxLen / 2) cut.take(lastSpace).trimEnd() else cut.trimEnd()
    }

    fun discordMessage(
        mainConfig: Config,
        botName: String,
        message: String,
    ): String {
        val pattern = mainConfig.string("discord.chat-pattern", "**%player_name%** » %message%")
        return pattern.replace("%player_name%", botName).replace("%message%", message)
    }

    fun telegramMessage(
        mainConfig: Config,
        botName: String,
        message: String,
    ): String {
        val pattern = mainConfig.string("telegram.chat-pattern", "**%player_name%** » %message%")
        return pattern.replace("%player_name%", botName).replace("%message%", message)
    }

    fun relayDiscord(config: Config): Boolean = config.bool("chat.relay-discord", true)

    fun relayTelegram(config: Config): Boolean = config.bool("chat.relay-telegram", true)
}
