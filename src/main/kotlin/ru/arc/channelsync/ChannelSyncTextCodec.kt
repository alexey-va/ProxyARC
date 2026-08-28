package ru.arc.channelsync

import ru.arc.ops.TelegramParseMode
import java.time.Instant

data class DiscordSyncTechnicalText(
    val userNamesById: Map<String, String> = emptyMap(),
    val roleNamesById: Map<String, String> = emptyMap(),
    val channelNamesById: Map<String, String> = emptyMap(),
)

data class TelegramSyncEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String? = null,
    val userId: Long? = null,
    val username: String? = null,
    val language: String? = null,
    val customEmojiId: String? = null,
)

internal data class TelegramMentionTarget(
    val telegramUserId: Long,
    val playerName: String,
)

internal data class DiscordMentionTarget(
    val discordUserId: String,
    val playerName: String,
)

internal class ChannelSyncIdentityResolver(
    private val telegramByDiscordUserId: (String) -> TelegramMentionTarget? = { null },
    private val discordByTelegramUserId: (Long) -> DiscordMentionTarget? = { null },
    private val discordByTelegramUsername: (String) -> DiscordMentionTarget? = { null },
) {
    fun telegramByDiscordUserId(discordUserId: String): TelegramMentionTarget? =
        telegramByDiscordUserId.invoke(discordUserId)

    fun discordByTelegramUserId(telegramUserId: Long): DiscordMentionTarget? =
        discordByTelegramUserId.invoke(telegramUserId)

    fun discordByTelegramUsername(username: String): DiscordMentionTarget? =
        discordByTelegramUsername.invoke(username)
}

internal data class TelegramTranslatedText(
    val text: String,
    val plainText: String,
    val parseMode: TelegramParseMode,
)

internal data class DiscordTranslatedText(
    val text: String,
    val allowedUserMentionIds: Set<String>,
)

internal class ChannelSyncTextCodec(
    private val identities: ChannelSyncIdentityResolver,
    private val telegramChannelMentionByDiscordId: (String) -> String? = { null },
    private val discordChannelMentionByTelegramUsername: (String) -> String? = { null },
) {
    fun discordToTelegram(message: DiscordSyncMessage): TelegramTranslatedText {
        val html = StringBuilder()
        val plain = StringBuilder()
        var cursor = 0
        DISCORD_TECHNICAL_TOKEN.findAll(message.text).forEach { match ->
            appendDiscordPlainSegment(message.text.substring(cursor, match.range.first), html, plain)
            val token = match.value
            when {
                USER_MENTION.matches(token) -> {
                    val userId = USER_MENTION.matchEntire(token)!!.groupValues[1]
                    val fallback = message.technical.userNamesById[userId] ?: "пользователь"
                    val target = identities.telegramByDiscordUserId(userId)
                    if (target == null) {
                        val rendered = "＠$fallback"
                        html.append(escapeHtml(rendered))
                        plain.append(rendered)
                    } else {
                        val label = "@${target.playerName}"
                        html.append("<a href=\"tg://user?id=${target.telegramUserId}\">${escapeHtml(label)}</a>")
                        plain.append(label)
                    }
                }
                ROLE_MENTION.matches(token) -> {
                    val roleId = ROLE_MENTION.matchEntire(token)!!.groupValues[1]
                    val rendered = "＠${message.technical.roleNamesById[roleId] ?: "роль"}"
                    html.append(escapeHtml(rendered))
                    plain.append(rendered)
                }
                CHANNEL_MENTION.matches(token) -> {
                    val channelId = CHANNEL_MENTION.matchEntire(token)!!.groupValues[1]
                    val mappedUsername = telegramChannelMentionByDiscordId(channelId)
                    if (mappedUsername != null) {
                        val rendered = "@$mappedUsername"
                        html.append("<a href=\"https://t.me/${escapeHtmlAttribute(mappedUsername)}\">${escapeHtml(rendered)}</a>")
                        plain.append(rendered)
                    } else {
                        val rendered = "#${message.technical.channelNamesById[channelId] ?: "канал"}"
                        html.append(escapeHtml(rendered))
                        plain.append(rendered)
                    }
                }
                CUSTOM_EMOJI.matches(token) -> {
                    val rendered = ":${CUSTOM_EMOJI.matchEntire(token)!!.groupValues[1]}:"
                    html.append(escapeHtml(rendered))
                    plain.append(rendered)
                }
                TIMESTAMP.matches(token) -> {
                    val epoch = TIMESTAMP.matchEntire(token)!!.groupValues[1].toLongOrNull()
                    val rendered = epoch?.let { Instant.ofEpochSecond(it).toString() } ?: "время"
                    html.append(escapeHtml(rendered))
                    plain.append(rendered)
                }
                SLASH_COMMAND.matches(token) -> {
                    val rendered = "/${SLASH_COMMAND.matchEntire(token)!!.groupValues[1]}"
                    html.append(escapeHtml(rendered))
                    plain.append(rendered)
                }
            }
            cursor = match.range.last + 1
        }
        appendDiscordPlainSegment(message.text.substring(cursor), html, plain)
        return TelegramTranslatedText(html.toString(), plain.toString(), TelegramParseMode.HTML)
    }

    fun telegramToDiscord(message: TelegramSyncMessage): DiscordTranslatedText {
        val entities = selectNonOverlappingEntities(message.text, message.entities)
        val output = StringBuilder()
        val allowedMentions = linkedSetOf<String>()
        var cursor = 0
        entities.forEach { entity ->
            output.append(neutralizeDiscordTechnical(message.text.substring(cursor, entity.offset)))
            val raw = message.text.substring(entity.offset, entity.offset + entity.length)
            output.append(renderTelegramEntity(entity, raw, allowedMentions))
            cursor = entity.offset + entity.length
        }
        output.append(neutralizeDiscordTechnical(message.text.substring(cursor)))
        return DiscordTranslatedText(output.toString(), allowedMentions)
    }

    fun safeDiscordPlainText(value: String): String = neutralizeDiscordTechnical(value)

    private fun renderTelegramEntity(
        entity: TelegramSyncEntity,
        raw: String,
        allowedMentions: MutableSet<String>,
    ): String =
        when (entity.type.lowercase()) {
            "text_mention" ->
                entity.userId?.let(identities::discordByTelegramUserId)?.let { target ->
                    allowedMentions += target.discordUserId
                    "<@${target.discordUserId}>"
                } ?: neutralizeDiscordTechnical(raw)
            "mention" -> {
                val username = entity.username ?: raw.removePrefix("@")
                identities.discordByTelegramUsername(username)?.let { target ->
                    allowedMentions += target.discordUserId
                    "<@${target.discordUserId}>"
                } ?: discordChannelMentionByTelegramUsername(username)?.let { "<#$it>" }
                    ?: neutralizeDiscordTechnical(raw)
            }
            "text_link" ->
                entity.url?.takeIf(::safeLink)?.let { url ->
                    "[${escapeDiscordLinkLabel(raw)}](${escapeDiscordLinkUrl(url)})"
                } ?: neutralizeDiscordTechnical(raw)
            "bold" -> "**${neutralizeDiscordTechnical(raw)}**"
            "italic" -> "*${neutralizeDiscordTechnical(raw)}*"
            "underline" -> "__${neutralizeDiscordTechnical(raw)}__"
            "strikethrough" -> "~~${neutralizeDiscordTechnical(raw)}~~"
            "spoiler" -> "||${neutralizeDiscordTechnical(raw)}||"
            "code" -> "`${neutralizeDiscordTechnical(raw).replace("`", "ʼ")}`"
            "pre" -> "```\n${neutralizeDiscordTechnical(raw).replace("```", "ʼʼʼ")}\n```"
            else -> neutralizeDiscordTechnical(raw)
        }

    private fun appendDiscordPlainSegment(
        raw: String,
        html: StringBuilder,
        plain: StringBuilder,
    ) {
        val safe = raw.replace("@everyone", "＠everyone").replace("@here", "＠here")
        html.append(escapeHtml(safe))
        plain.append(safe)
    }

    companion object {
        private val USER_MENTION = Regex("<@!?(\\d{17,20})>")
        private val ROLE_MENTION = Regex("<@&(\\d{17,20})>")
        private val CHANNEL_MENTION = Regex("<#(\\d{17,20})>")
        private val CUSTOM_EMOJI = Regex("<a?:([A-Za-z0-9_]{2,32}):\\d{17,20}>")
        private val TIMESTAMP = Regex("<t:(\\d{1,12})(?::[tTdDfFR])?>")
        private val SLASH_COMMAND = Regex("</([A-Za-z0-9_-]{1,32})(?: [A-Za-z0-9_-]{1,32})*:\\d{17,20}>")
        private val DISCORD_TECHNICAL_TOKEN =
            Regex(
                "<@!?\\d{17,20}>|<@&\\d{17,20}>|<#\\d{17,20}>|<a?:[A-Za-z0-9_]{2,32}:\\d{17,20}>|" +
                    "<t:\\d{1,12}(?::[tTdDfFR])?>|</[A-Za-z0-9_-]{1,32}(?: [A-Za-z0-9_-]{1,32})*:\\d{17,20}>",
            )
        private val RAW_DISCORD_MENTION = Regex("<(?=@!?\\d{17,20}>|@&\\d{17,20}>|#\\d{17,20}>)")

        private fun selectNonOverlappingEntities(
            text: String,
            entities: List<TelegramSyncEntity>,
        ): List<TelegramSyncEntity> {
            var cursor = 0
            return entities
                .filter { it.offset >= 0 && it.length > 0 && it.offset + it.length <= text.length }
                .sortedWith(compareBy<TelegramSyncEntity>({ it.offset }, { entityPriority(it.type) }, { -it.length }))
                .mapNotNull { entity ->
                    if (entity.offset < cursor) return@mapNotNull null
                    cursor = entity.offset + entity.length
                    entity
                }
        }

        private fun entityPriority(type: String): Int =
            when (type.lowercase()) {
                "text_mention", "mention" -> 0
                "text_link" -> 1
                "code", "pre" -> 2
                else -> 3
            }

        private fun neutralizeDiscordTechnical(value: String): String =
            RAW_DISCORD_MENTION.replace(value) { "\\<" }
                .replace("@everyone", "＠everyone")
                .replace("@here", "＠here")

        private fun escapeHtml(value: String): String = telegramHtmlEscape(value)

        private fun escapeHtmlAttribute(value: String): String = telegramHtmlEscape(value).replace("'", "&#39;")

        private fun safeLink(value: String): Boolean =
            value.startsWith("https://") || value.startsWith("http://") || value.startsWith("tg://")

        private fun escapeDiscordLinkLabel(value: String): String = value.replace("\\", "\\\\").replace("]", "\\]")

        private fun escapeDiscordLinkUrl(value: String): String = value.replace(")", "%29").replace(" ", "%20")
    }
}

internal fun telegramHtmlEscape(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
