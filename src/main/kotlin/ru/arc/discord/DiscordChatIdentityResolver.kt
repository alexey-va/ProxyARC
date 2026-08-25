package ru.arc.discord

/** Resolves the authenticated Discord sender to the canonical name shown by the chat bridge. */
internal class DiscordChatIdentityResolver(
    private val playerNameByDiscordUserId: (String) -> String? = { null },
) {
    fun resolve(
        discordUserId: String,
        discordDisplayName: String,
    ): String =
        playerNameByDiscordUserId(discordUserId)
            ?.takeIf(String::isNotBlank)
            ?: discordDisplayName
}
