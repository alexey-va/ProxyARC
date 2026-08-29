package ru.arc.join

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import ru.arc.Utils.mm
import ru.arc.core.modules.JoinMessagesModule
import ru.arc.core.modules.JoinMessageCatalogModule
import ru.arc.discord.DiscordBot
import ru.arc.velocity.Velocity
import java.util.UUID
import java.util.concurrent.CompletableFuture

class VelocityAnnouncementPlayer(
    private val player: Player,
) : AnnouncementPlayer {
    override val playerId: UUID get() = player.uniqueId
    override val playerName: String get() = player.username
    override val connectionIdentity: Any get() = player
    override val active: Boolean get() = player.isActive
}

class RedisJoinMessageSource : JoinMessageSource {
    override fun load(playerName: String, kind: JoinAnnouncementKind): CompletableFuture<String?> =
        JoinMessagesModule.loadAsync(playerName).thenApply { messages ->
            JoinMessageCatalogModule.selectedMessage(messages, kind)
        }
}

class VelocityJoinAnnouncementSink(
    private val proxyServer: ProxyServer,
    private val config: JoinAnnouncementConfig,
    private val logger: Logger,
) : JoinAnnouncementSink {
    override fun publish(announcement: PublishedAnnouncement) {
        val joinType = announcement.kind.toDiscordJoinType()
        deliver("Discord", announcement) {
            Velocity.discordBot?.sendJoinEmbed(announcement.playerName, joinType, announcement.customMessage)
        }
        deliver("Telegram", announcement) {
            Velocity.telegramBot?.sendJoinMessage(announcement.playerName, joinType, announcement.customMessage)
        }
        deliver("Minecraft", announcement) {
            val component = mm(config.minecraftMessage(announcement))
            proxyServer.allPlayers.forEach { player -> player.sendMessage(component) }
        }
    }

    private fun deliver(
        surface: String,
        announcement: PublishedAnnouncement,
        operation: () -> Unit,
    ) {
        runCatching(operation).onFailure { error ->
            logger.error(
                "Could not publish {} {} announcement for {}",
                surface,
                announcement.kind,
                announcement.playerName,
                error,
            )
        }
    }
}

object VelocityProxyLifecycle : ProxyLifecycle {
    override val shuttingDown: Boolean get() = Velocity.isShuttingDown.get()
}

private fun JoinAnnouncementKind.toDiscordJoinType(): DiscordBot.JoinType =
    when (this) {
        JoinAnnouncementKind.FIRST_TIME -> DiscordBot.JoinType.FIRST_TIME
        JoinAnnouncementKind.JOIN -> DiscordBot.JoinType.JOIN
        JoinAnnouncementKind.LEAVE -> DiscordBot.JoinType.LEAVE
    }
