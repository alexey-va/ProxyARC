package ru.arc.discord

import net.dv8tion.jda.api.entities.Message

/**
 * Turns Discord markup into plain chat text for in-game relay and the assistant.
 * Example: `<@123456789>` → `@addscoren`
 */
object DiscordMessageText {
    private val USER_MENTION = Regex("<@!?(\\d+)>")
    private val ROLE_MENTION = Regex("<@&(\\d+)>")
    private val CHANNEL_MENTION = Regex("<#(\\d+)>")
    private val CUSTOM_EMOJI = Regex("<a?:(\\w+):\\d+>")

    fun formatForRelay(message: Message): String =
        formatContent(
            raw = message.contentRaw,
            userNamesById = message.mentions.users.associate { it.id to it.name },
            roleNamesById = message.mentions.roles.associate { it.id to it.name },
            channelNamesById = message.mentions.channels.associate { it.id to it.name },
        )

    internal fun formatContent(
        raw: String,
        userNamesById: Map<String, String> = emptyMap(),
        roleNamesById: Map<String, String> = emptyMap(),
        channelNamesById: Map<String, String> = emptyMap(),
    ): String {
        var text = raw

        text = USER_MENTION.replace(text) { match ->
            val id = match.groupValues[1]
            userNamesById[id]?.let { "@$it" } ?: match.value
        }
        text = ROLE_MENTION.replace(text) { match ->
            val id = match.groupValues[1]
            roleNamesById[id]?.let { "@$it" } ?: match.value
        }
        text = CHANNEL_MENTION.replace(text) { match ->
            val id = match.groupValues[1]
            channelNamesById[id]?.let { "#$it" } ?: match.value
        }
        text = CUSTOM_EMOJI.replace(text, ":$1:")
        return text.trim()
    }
}
