package ru.arc.ai

import ru.arc.config.Config

object AssistantChatFormat {
    const val DEFAULT_DISPLAY_NAME = "скорен"

    /** CMI `Chat.yml` → `GeneralFormat` (global / `!` shout). */
    const val CMI_GENERAL_FORMAT = "%suffix% &7%name% &8» &f%message%"

    /** Bot marker in suffix slot (instead of `%luckperms_suffix%`). Empty string = no marker. */
    const val DEFAULT_SUFFIX = "&e◇"

    fun displayName(config: Config): String =
        config.string("chat.display-name", DEFAULT_DISPLAY_NAME)

    fun suffix(config: Config): String =
        config.string("chat.suffix", DEFAULT_SUFFIX)

    fun inGameMessage(
        config: Config,
        message: String,
    ): String {
        val format = config.string("chat.message-format", CMI_GENERAL_FORMAT)
        return applyPlaceholders(format, config, message)
    }

    internal fun applyPlaceholders(
        format: String,
        config: Config,
        message: String,
    ): String {
        val name = displayName(config)
        val suffix = suffix(config)
        return format
            .replace("%suffix%", suffix)
            .replace("%luckperms_suffix%", suffix)
            .replace("%name%", name)
            .replace("{displayName}", name)
            .replace("%message%", message)
            .replace("{message}", message)
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
