package ru.arc.discord

import net.dv8tion.jda.api.entities.Message

/**
 * Turns Discord markup into plain chat text for in-game relay and the assistant.
 * Example: `<@123456789>` → `@addscoren`
 */
object DiscordMessageText {
    fun formatForRelay(message: Message): String =
        DiscordMessageCodec().discordToMinecraft(message)

    internal fun formatContent(
        raw: String,
        userNamesById: Map<String, String> = emptyMap(),
        roleNamesById: Map<String, String> = emptyMap(),
        channelNamesById: Map<String, String> = emptyMap(),
    ): String {
        return DiscordMessageCodec.formatDiscordContent(raw, userNamesById, roleNamesById, channelNamesById)
    }
}
