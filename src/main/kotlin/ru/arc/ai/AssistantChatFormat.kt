package ru.arc.ai

import ru.arc.config.Config

object AssistantChatFormat {
    const val DEFAULT_DISPLAY_NAME = "Скорен"
    const val MODEL_SKIP_TOKEN = "SKIP"
    private const val LEGACY_SKIP_TOKEN = "пропускаю"

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
    const val DEFAULT_OBSERVE_FORMAT = "[%time% %delta%] %player% » %message%"
    const val MAX_REPLY_PARTS = 2

    fun displayName(config: Config): String =
        config.string("chat.display-name", DEFAULT_DISPLAY_NAME)

    /** Model signals no public reply — exact SKIP (legacy: пропускаю). */
    fun isModelSkip(text: String): Boolean {
        val normalized =
            text
                .trim()
                .trimEnd('.', '!', '?', ',', ';', ':')
                .trim()
        if (normalized.equals(MODEL_SKIP_TOKEN, ignoreCase = true)) {
            return true
        }
        if (normalized.equals(LEGACY_SKIP_TOKEN, ignoreCase = true)) {
            return true
        }
        val lastLine =
            text
                .trim()
                .lines()
                .lastOrNull()
                ?.trim()
                ?.trimEnd('.', '!', '?', ',', ';', ':')
                ?.trim()
                .orEmpty()
        return lastLine.equals(MODEL_SKIP_TOKEN, ignoreCase = true) ||
            lastLine.equals(LEGACY_SKIP_TOKEN, ignoreCase = true)
    }

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
        val trimmed = rawReply.trim()
        if (isLowQualityReply(trimmed)) {
            return NormalizedChatReply(skipReason = "low quality reply")
        }
        val parts = splitReplyParts(config, rawReply)
        if (parts.isEmpty()) {
            return when {
                trimmed.isEmpty() -> NormalizedChatReply(skipReason = "empty after trim")
                AssistantChatFormat.isModelSkip(trimmed) ->
                    NormalizedChatReply(skipReason = "model said SKIP")
                else -> NormalizedChatReply(skipReason = "empty after length clamp")
            }
        }
        return NormalizedChatReply(parts = parts)
    }

    private fun isLowQualityReply(text: String): Boolean =
        text.isNotEmpty() &&
            !AssistantChatFormat.isModelSkip(text) &&
            (
                text.none { it.isLetter() } ||
                    text.none { it in '\u0400'..'\u04FF' } ||
                    text.codePoints().anyMatch { codePoint ->
                        if (!Character.isLetter(codePoint)) {
                            false
                        } else {
                            Character.UnicodeScript.of(codePoint) !in
                                setOf(
                                    Character.UnicodeScript.CYRILLIC,
                                    Character.UnicodeScript.LATIN,
                                )
                        }
                    } ||
                    text
                        .trimStart { it.isWhitespace() || it in ",.;:!?—–-" }
                        .trim()
                        .lowercase() in setOf("сорян", "ок", "ага", "пон", "ясно") ||
                    Regex("""(?m)\[\d{1,2}:\d{2}(?::\d{2})?]\s+\S+\s+»""")
                        .containsMatchIn(text) ||
                    // SentencePiece-style control markers and the alternate transcript format
                    // are model/context leakage, never player-facing chat.
                    text.contains("<｜") ||
                    text.contains("｜>") ||
                    Regex("""(?m)\|\s*\d{1,2}:\d{2}(?::\d{2})?\s*\|\s*\S+\s+»""")
                        .containsMatchIn(text) ||
                    Regex("""\[[^\]\r\n]{1,80}]""").containsMatchIn(text) ||
                    Regex(
                        """(?iu)^\s*\d+\s+(?:секунд(?:а|ы)?|минут(?:а|ы)?|час(?:а|ов)?|дн(?:я|ей))\s+назад(?:\s|$)""",
                    ).containsMatchIn(text) ||
                    Regex(
                        """(?im)(?:^\s*(?:#!|```|(?:rm|curl|wget|sudo|chmod|chown|ssh|scp|bash|sh|python\d*|node)\b)|/tmp/|\$\()""",
                    ).containsMatchIn(text) ||
                    Regex("""(?iu)(?:^|[;\s])(?:import|request|function|class)\s*;""")
                        .containsMatchIn(text) ||
                    hasDuplicateReplySegment(text) ||
                    Regex("""(?iu)([\p{L}])\1{3,}""").containsMatchIn(text) ||
                    text
                        .split(Regex("""[^\p{L}]+"""))
                        .filter { it.isNotEmpty() }
                        .any { token ->
                            token.any { it in 'A'..'Z' || it in 'a'..'z' } &&
                                token.any { it in '\u0400'..'\u04FF' }
                        }
            )

    private fun hasDuplicateReplySegment(text: String): Boolean {
        val segments =
            text
                .split(Regex("""(?:\r?\n)+|(?<=[.!?])\s+"""))
                .map {
                    it
                        .trim()
                        .trimEnd('.', '!', '?', ',', ';', ':')
                        .trim()
                        .lowercase()
                }
                .filter { it.length >= 4 }
        return segments.size >= 2 && segments.size != segments.distinct().size
    }

    /** Split on blank line; long single blocks are chunked into multiple chat messages. */
    fun splitReplyParts(
        config: Config,
        rawReply: String,
    ): List<String> {
        val trimmed = rawReply.trim()
        if (trimmed.isEmpty() || AssistantChatFormat.isModelSkip(trimmed)) {
            return emptyList()
        }
        val maxLen = maxMessageLength(config)
        val parts = mutableListOf<String>()
        trimmed
            .split("\n\n")
            .map { block ->
                block
                    .replace('\n', ' ')
                    .trim()
                    .trimStart { it.isWhitespace() || it in ",.;:!?—–-" }
                    .trim()
            }
            .filter { it.isNotEmpty() && !AssistantChatFormat.isModelSkip(it) }
            .forEach { block ->
                for (chunk in chunkBlock(block, maxLen)) {
                    parts.add(chunk)
                    if (parts.size >= MAX_REPLY_PARTS) return parts
                }
            }
        return parts
    }

    internal fun chunkBlock(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val chunks = mutableListOf<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            if (rest.length <= maxLen) {
                chunks.add(rest)
                break
            }
            val chunk = clampForChat(rest, maxLen)
            if (chunk.isEmpty()) break
            chunks.add(chunk)
            rest = rest.substring(chunk.length).trimStart()
        }
        return chunks
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
