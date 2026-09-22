package ru.arc.events

import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import java.util.Locale

data class ArcEventsChatSettings(
    val enabled: Boolean = true,
    val allowedServers: Set<String> = DEFAULT_ALLOWED_SERVERS,
) {
    fun allows(serverName: String): Boolean =
        enabled && normalize(serverName) in allowedServers

    companion object {
        val DEFAULT_ALLOWED_SERVERS: Set<String> = setOf("parkour")

        fun from(config: Config): ArcEventsChatSettings {
            val servers =
                config
                    .stringList("allowed-servers", DEFAULT_ALLOWED_SERVERS.toList())
                    .map(::normalize)
                    .filter(String::isNotEmpty)
                    .toSet()
            return ArcEventsChatSettings(
                enabled = config.bool("enabled", true),
                allowedServers = servers,
            )
        }

        private fun normalize(serverName: String): String = serverName.trim().lowercase(Locale.ROOT)
    }

    private fun normalize(serverName: String): String = serverName.trim().lowercase(Locale.ROOT)
}

object ArcEventsChatConfig {
    fun load(): ArcEventsChatSettings =
        ArcEventsChatSettings.from(ProxyConfigs.module("event-chat.yml"))
}
