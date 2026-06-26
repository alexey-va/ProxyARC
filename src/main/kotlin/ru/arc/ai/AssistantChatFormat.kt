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

    fun displayName(config: Config): String =
        config.string("chat.display-name", DEFAULT_DISPLAY_NAME)

    fun shoutPrefix(config: Config): String =
        config.string("chat.shout-prefix", CMI_SHOUT_PREFIX)

    fun suffix(config: Config): String =
        config.string("chat.suffix", DEFAULT_SUFFIX)

    fun maxMessageLength(config: Config): Int =
        config.integer("chat.max-message-length", DEFAULT_MAX_MESSAGE_LENGTH).coerceIn(20, 256)

    fun inGameMessage(
        config: Config,
        message: String,
    ): String {
        val format = config.string("chat.message-format", CMI_GLOBAL_FORMAT)
        return applyPlaceholders(format, config, message)
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
        val single = rawReply
            .substringBefore("\n\n")
            .replace('\n', ' ')
            .trim()
        if (single.isEmpty()) {
            return NormalizedChatReply(skipReason = "empty after trim")
        }
        if (single.equals("пропускаю", ignoreCase = true)) {
            return NormalizedChatReply(skipReason = "model said пропускаю")
        }
        val clamped = clampForChat(single, maxMessageLength(config))
        if (clamped.isEmpty()) {
            return NormalizedChatReply(skipReason = "empty after length clamp")
        }
        return NormalizedChatReply(text = clamped)
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
