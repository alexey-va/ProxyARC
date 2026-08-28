package ru.arc.ops

import ru.arc.config.Config
import ru.arc.config.ProxyConfigs

class ProxyOpsHttpConfig private constructor(
    val enabled: Boolean,
    val token: String,
    val bindHost: String,
    val bindPort: Int,
    val simulateEnabled: Boolean,
    val logsEnabled: Boolean,
    val discordReadEnabled: Boolean,
    val discordWriteEnabled: Boolean,
    val discordAdminEnabled: Boolean,
    val discordAllowedGuildIds: Set<String>,
    val discordAllowedChannelIds: Set<String>,
    val discordWriteChannelIds: Set<String>,
    val discordMaxHistory: Int,
    val telegramReadEnabled: Boolean,
    val telegramWriteEnabled: Boolean,
    val telegramAdminEnabled: Boolean,
    val telegramAllowedChatIds: Set<String>,
    val telegramWriteChatIds: Set<String>,
    val telegramAdminChatIds: Set<String>,
) {
    constructor(config: Config) : this(
        enabled = config.bool("enabled", false),
        token = config.string("token", ""),
        bindHost = config.string("bind-host", "127.0.0.1"),
        bindPort = config.integer("bind-port", 25825),
        simulateEnabled = config.bool("simulate-enabled", true),
        logsEnabled = config.bool("logs-enabled", true),
        discordReadEnabled = config.bool("discord-read-enabled", false),
        discordWriteEnabled = config.bool("discord-write-enabled", false),
        discordAdminEnabled = config.bool("discord-admin-enabled", false),
        discordAllowedGuildIds = config.idSet("discord-allowed-guild-ids"),
        discordAllowedChannelIds = config.idSet("discord-allowed-channel-ids"),
        discordWriteChannelIds = config.idSet("discord-write-channel-ids"),
        discordMaxHistory = config.integer("discord-max-history", 50).coerceIn(1, 100),
        telegramReadEnabled = config.bool("telegram-read-enabled", false),
        telegramWriteEnabled = config.bool("telegram-write-enabled", false),
        telegramAdminEnabled = config.bool("telegram-admin-enabled", false),
        telegramAllowedChatIds = config.idSet("telegram-allowed-chat-ids"),
        telegramWriteChatIds = config.idSet("telegram-write-chat-ids"),
        telegramAdminChatIds = config.idSet("telegram-admin-chat-ids"),
    )

    companion object {
        private val DISABLED =
            ProxyOpsHttpConfig(
                enabled = false,
                token = "",
                bindHost = "127.0.0.1",
                bindPort = 25825,
                simulateEnabled = false,
                logsEnabled = false,
                discordReadEnabled = false,
                discordWriteEnabled = false,
                discordAdminEnabled = false,
                discordAllowedGuildIds = emptySet(),
                discordAllowedChannelIds = emptySet(),
                discordWriteChannelIds = emptySet(),
                discordMaxHistory = 50,
                telegramReadEnabled = false,
                telegramWriteEnabled = false,
                telegramAdminEnabled = false,
                telegramAllowedChatIds = emptySet(),
                telegramWriteChatIds = emptySet(),
                telegramAdminChatIds = emptySet(),
            )

        @Volatile
        private var instance: ProxyOpsHttpConfig = DISABLED

        fun current(): ProxyOpsHttpConfig = instance

        fun reload() {
            instance = ProxyOpsHttpConfig(ProxyConfigs.module("ops-http.yml"))
        }

        private fun Config.idSet(path: String): Set<String> =
            stringList(path)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toCollection(linkedSetOf())
    }
}
