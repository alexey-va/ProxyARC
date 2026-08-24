package ru.arc.discord

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

/** One safe translation boundary for messages crossing Minecraft and Discord. */
internal class DiscordMessageCodec(
    private val identityByPlayerName: (String) -> DiscordIdentityLink? = { null },
) {
    fun discordToMinecraft(message: Message): String {
        val content =
            formatDiscordContent(
                raw = message.contentRaw,
                userNamesById = message.mentions.users.associate { it.id to it.name },
                roleNamesById = message.mentions.roles.associate { it.id to it.name },
                channelNamesById = message.mentions.channels.associate { it.id to it.name },
            )
        val extras = buildList {
            message.attachments.forEach { attachment ->
                add("📎 ${attachment.fileName}: ${attachment.url}")
            }
            message.stickers.forEach { sticker -> add(":${sticker.name}:") }
        }
        return (listOf(content).filter(String::isNotBlank) + extras).joinToString("\n").trim()
    }

    fun minecraftToDiscord(
        raw: String,
        guild: Guild?,
    ): String {
        var text = sanitizeMinecraftFormatting(raw)
        text = PLAYER_TAG.replace(text) { match ->
            val playerName = match.groupValues[1]
            identityByPlayerName(playerName)?.let { "<@${it.discordUserId}>" } ?: match.value
        }
        if (guild != null) {
            text = ROLE_TAG.replace(text) { match ->
                guild.getRolesByName(match.groupValues[1], true).firstOrNull()?.asMention ?: match.value
            }
            text = CHANNEL_TAG.replace(text) { match ->
                guild.getTextChannelsByName(match.groupValues[1], true).firstOrNull()?.asMention ?: match.value
            }
            text = COLON_EMOJI.replace(text) { match ->
                guild.getEmojisByName(match.groupValues[1], true).firstOrNull()?.asMention ?: match.value
            }
        }
        return text.trim()
    }

    fun messageData(content: String): MessageCreateData =
        MessageCreateBuilder()
            .setContent(content)
            .setAllowedMentions(emptySet())
            .build()

    fun minecraftBody(text: String): Component {
        var cursor = 0
        var result = Component.empty()
        LINK_TOKEN.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                result = result.append(Component.text(text.substring(cursor, match.range.first)))
            }
            val maskedLabel = match.groupValues[1]
            val maskedUrl = match.groupValues[2]
            val directUrl = match.groupValues[3]
            val trailingPunctuation =
                if (maskedUrl.isEmpty()) directUrl.takeLastWhile { it in URL_TRAILING_PUNCTUATION } else ""
            val url = maskedUrl.ifEmpty { directUrl.dropLast(trailingPunctuation.length) }
            val label = maskedLabel.ifEmpty { url }
            result =
                result.append(
                    Component.text(label, NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url)),
                )
            if (trailingPunctuation.isNotEmpty()) {
                result = result.append(Component.text(trailingPunctuation))
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) result = result.append(Component.text(text.substring(cursor)))
        return result
    }

    companion object {
        private val USER_MENTION = Regex("<@!?(\\d+)>")
        private val ROLE_MENTION = Regex("<@&(\\d+)>")
        private val CHANNEL_MENTION = Regex("<#(\\d+)>")
        private val CUSTOM_EMOJI = Regex("<a?:(\\w+):\\d+>")
        private val PLAYER_TAG = Regex("(?<![\\w<])@([A-Za-z0-9_]{3,16})")
        private val ROLE_TAG = Regex("(?<![\\w<])@([\\p{L}\\p{N}_-]{2,32})")
        private val CHANNEL_TAG = Regex("(?<![\\w</=])#([\\p{L}\\p{N}_-]{2,32})")
        private val COLON_EMOJI = Regex("(?<![\\w<]):([A-Za-z0-9_]{2,32}):(?!\\d*>)")
        private val LEGACY_COLOR = Regex("(?i)[§&][0-9A-FK-ORX]")
        private val MINI_MESSAGE_FORMATTING =
            Regex(
                "(?i)</?(?:black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white|bold|b|italic|i|underlined|u|strikethrough|st|obfuscated|reset|#[0-9a-f]{6}|gradient(?::[^>]*)?|rainbow(?::[^>]*)?)>",
            )
        private val LINK_TOKEN =
            Regex("\\[([^]\\n]{1,80})]\\((https?://[^\\s)]+)\\)|(https?://[^\\s<>]+)")
        private const val URL_TRAILING_PUNCTUATION = ".,!?;:"

        internal fun formatDiscordContent(
            raw: String,
            userNamesById: Map<String, String> = emptyMap(),
            roleNamesById: Map<String, String> = emptyMap(),
            channelNamesById: Map<String, String> = emptyMap(),
        ): String {
            var text = raw
            text = USER_MENTION.replace(text) { match ->
                "@${userNamesById[match.groupValues[1]] ?: "пользователь"}"
            }
            text = ROLE_MENTION.replace(text) { match ->
                "@${roleNamesById[match.groupValues[1]] ?: "роль"}"
            }
            text = CHANNEL_MENTION.replace(text) { match ->
                "#${channelNamesById[match.groupValues[1]] ?: "канал"}"
            }
            text = CUSTOM_EMOJI.replace(text, ":$1:")
            return text.trim()
        }

        internal fun sanitizeMinecraftFormatting(raw: String): String =
            MINI_MESSAGE_FORMATTING.replace(LEGACY_COLOR.replace(raw, ""), "")
    }
}
