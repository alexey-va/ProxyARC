package ru.arc.velocity.listeners

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import org.slf4j.LoggerFactory
import ru.arc.chat.ChatModeService
import ru.arc.join.AnnouncementPermissions
import ru.arc.join.JoinSessionAnnouncements
import ru.arc.join.VelocityAnnouncementPlayer
import ru.arc.velocity.Velocity

class JoinListener(
    private val announcements: JoinSessionAnnouncements,
) {
    @Subscribe(async = true)
    fun onPlayerAuthenticated(event: PostLoginEvent) {
        if (Velocity.isShuttingDown.get()) return
        val player = event.player
        Velocity.playerActivityTracker?.markSeen(player.uniqueId)
        ChatModeService.track(player.uniqueId)
        Velocity.playerListAnnouncer?.addPlayer(
            player.uniqueId,
            player.username,
            player.currentServer.map { it.serverInfo.name }.orElse(""),
        )
        Velocity.discordBot?.refreshPlayerListFromProxy()
        announcements.onPostLogin(
            VelocityAnnouncementPlayer(player),
            AnnouncementPermissions(
                external = player.hasPermission(EXTERNAL_JOIN_ANNOUNCEMENT_PERMISSION),
            ),
        )
    }

    @Subscribe(async = true)
    fun onPlayerLeave(event: DisconnectEvent) {
        val player = event.player
        announcements.onDisconnect(VelocityAnnouncementPlayer(player))
        if (event.loginStatus == DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
            Velocity.playerActivityTracker?.markSeen(player.uniqueId)
        }
        ChatModeService.untrack(player.uniqueId)
        Velocity.playerListAnnouncer?.removePlayer(player.uniqueId)
        if (!Velocity.isShuttingDown.get()) {
            Velocity.discordBot?.refreshPlayerListFromProxy()
        }
    }

    @Subscribe(async = true)
    fun onChangeServer(event: ServerConnectedEvent) {
        val server = event.server.serverInfo.name
        val username = event.player.username
        Velocity.playerListAnnouncer?.updatePlayer(event.player.uniqueId, username, server)
        Velocity.discordBot?.refreshPlayerListFromProxy()
        val discord = Velocity.discordBot
        if (discord?.isVerificationBackendAllowed(server) == true) {
            discord.reconcileIdentity(event.player.uniqueId, username).whenComplete { _, error ->
                if (error != null) {
                    log.warn("Discord identity reconciliation failed for {}", username, error)
                }
            }
        }
        Velocity.telegramBot?.takeIf { it.isIdentityBackendAllowed(server) }
            ?.refreshIdentity(event.player.uniqueId, username)
    }

    companion object {
        internal const val EXTERNAL_JOIN_ANNOUNCEMENT_PERMISSION = "arc.join-message.external"
        private val log = LoggerFactory.getLogger(JoinListener::class.java)
    }
}
