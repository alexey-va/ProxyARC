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
        announcement.destinations().forEach { destination ->
            when (destination) {
                JoinAnnouncementDestination.DISCORD ->
                    deliver("Discord", announcement) {
                        Velocity.discordBot?.sendJoinEmbed(
                            announcement.playerName,
                            announcement.kind.toDiscordJoinType(),
                            announcement.customMessage,
                        )
                    }
                JoinAnnouncementDestination.TELEGRAM ->
                    deliver("Telegram", announcement) {
                        Velocity.telegramBot?.sendJoinMessage(
                            announcement.playerName,
                            announcement.kind.toDiscordJoinType(),
                            announcement.customMessage,
                        )
                    }
                JoinAnnouncementDestination.MINECRAFT ->
                    deliver("Minecraft", announcement) {
                        val component = mm(config.minecraftMessage(announcement))
                        proxyServer.allPlayers
                            .filter { player ->
                                val serverName = player.currentServer.map { it.serverInfo.name }.orElse(null)
                                config.allowsMinecraftRecipient(serverName)
                            }
                            .forEach { player -> player.sendMessage(component) }
                    }
            }
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

internal enum class JoinAnnouncementDestination {
    DISCORD,
    TELEGRAM,
    MINECRAFT,
}

internal fun PublishedAnnouncement.destinations(): List<JoinAnnouncementDestination> =
    if (publishExternally) {
        listOf(
            JoinAnnouncementDestination.DISCORD,
            JoinAnnouncementDestination.TELEGRAM,
            JoinAnnouncementDestination.MINECRAFT,
        )
    } else {
        listOf(JoinAnnouncementDestination.MINECRAFT)
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
