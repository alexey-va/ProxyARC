package ru.arc.discord

import com.velocitypowered.api.proxy.ProxyServer

internal data class DiscordOnlinePlayer(
    val name: String,
    val server: String?,
)

internal data class DiscordNetworkSnapshot(
    val players: List<DiscordOnlinePlayer>,
    val knownServers: Set<String>,
) {
    val online: Int get() = players.size

    fun onServer(server: String): List<DiscordOnlinePlayer> =
        players.filter { it.server.equals(server, ignoreCase = true) }

    fun serverFor(playerName: String): String? =
        players.firstOrNull { it.name.equals(playerName, ignoreCase = true) }?.server

    companion object {
        fun capture(proxy: ProxyServer): DiscordNetworkSnapshot =
            DiscordNetworkSnapshot(
                players =
                    proxy.allPlayers.map { player ->
                        DiscordOnlinePlayer(
                            player.username,
                            player.currentServer.map { it.serverInfo.name }.orElse(null),
                        )
                    }.sortedBy { it.name.lowercase() },
                knownServers = proxy.allServers.mapTo(sortedSetOf()) { it.serverInfo.name },
            )
    }
}
