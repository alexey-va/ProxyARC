package ru.arc.ai

import ru.arc.config.Config

object AssistantChatFormat {
    const val DEFAULT_DISPLAY_NAME = "скорен"
    const val DEFAULT_MESSAGE_FORMAT = "<yellow>◇ <gray>%name% <dark_gray>» <white>%message%"

    fun displayName(config: Config): String =
        config.string("chat.display-name", DEFAULT_DISPLAY_NAME)

    fun inGameMessage(
        config: Config,
        message: String,
    ): String {
        val format = config.string("chat.message-format", DEFAULT_MESSAGE_FORMAT)
        return format
            .replace("%name%", displayName(config))
            .replace("%message%", message)
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
