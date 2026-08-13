package ru.arc.velocity.listeners

import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import org.slf4j.LoggerFactory
import ru.arc.Utils.mm
import ru.arc.config.Config
import ru.arc.chat.ChatModeService
import ru.arc.core.delayed
import ru.arc.core.modules.JoinMessagesModule
import ru.arc.discord.DiscordBot
import ru.arc.velocity.Velocity
import ru.arc.xserver.JoinMessages
import java.util.concurrent.CompletableFuture

class JoinListener(
    private val proxyServer: ProxyServer,
    private val config: Config,
) {

    @Subscribe(async = true)
    fun onPlayerAuthenticated(event: PostLoginEvent) {
        if (Velocity.isShuttingDown.get()) return
        Velocity.playerActivityTracker?.markSeen(event.player.uniqueId)
    }

    @Subscribe(async = true)
    fun onPlayerJoin(event: LoginEvent) {
        if (Velocity.isShuttingDown.get()) return
        ChatModeService.track(event.player.uniqueId)
        delayed(20) { joinMessage(event.player) }
        val serverName =
            event.player.currentServer
                .map { it.serverInfo.name }
                .orElse("")
        Velocity.playerListAnnouncer?.addPlayer(
            event.player.uniqueId,
            event.player.username,
            serverName,
        )
        Velocity.discordBot?.refreshPlayerListFromProxy()
        val allow =
            Velocity.antibot?.processPlayerJoin(
                event.player.username,
                event.player.uniqueId,
                proxyServer.playerCount,
            ) ?: true
        if (!allow) {
            log.info("Antibot kicked player {}", event.player.username)
            event.setResult(
                ResultedEvent.ComponentResult.denied(
                    mm(config.string("messages.antibot", "<red>Вы были отключены от сервера! Зайдите позже!")),
                ),
            )
        }
    }

    @Subscribe(async = true)
    fun onPlayerLeave(event: DisconnectEvent) {
        if (Velocity.isShuttingDown.get()) return
        if (event.loginStatus == DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) {
            Velocity.playerActivityTracker?.markSeen(event.player.uniqueId)
        }
        ChatModeService.untrack(event.player.uniqueId)
        Velocity.discordBot?.refreshPlayerListFromProxy()
        if (!event.player.hasPermission("arc.join-message.leave")) return
        delayed(20) { leaveMessage(event.player) }
        Velocity.playerListAnnouncer?.removePlayer(event.player.uniqueId)
        Velocity.antibot?.processPlayerLeave(event.player.uniqueId)
    }

    @Subscribe(async = true)
    fun onChangeServer(event: ServerConnectedEvent) {
        val server = event.server.serverInfo.name
        val username = event.player.username
        Velocity.playerListAnnouncer?.updatePlayer(event.player.uniqueId, username, server)
        Velocity.discordBot?.refreshPlayerListFromProxy()
    }

    private fun sendMessageToAll(component: Component) {
        proxyServer.allPlayers.forEach { it.sendMessage(component) }
    }

    private fun joinMessage(player: Player) {
        if (Velocity.isShuttingDown.get()) return
        try {
            if (!player.isActive) return
            val firstJoin = Velocity.firstJoinData ?: return
            val firstTime = firstJoin.firstTimeJoin(player.username)
            if (firstTime) {
                firstJoin.markAsJoined(player.username)
                if (!player.hasPermission("arc.join-message.first")) return
                Velocity.discordBot?.sendJoinEmbed(player.username, DiscordBot.JoinType.FIRST_TIME, null)
                Velocity.telegramBot?.sendJoinMessage(player.username, DiscordBot.JoinType.FIRST_TIME, null)
                var message =
                    config.string("messages.first-join", "<gray>Игрок <green>%player_name% <gray>присоединился впервые!")
                message = message.replace("%player_name%", player.username)
                message = config.string("messages.join-prefix", "<dark_green>❖ ") + message
                sendMessageToAll(mm(message))
            } else {
                if (!player.hasPermission("arc.join-message.join")) return
                loadCustomMessage(player.username, JoinMessages::randomJoinMessage)
                    .whenComplete { customMessage, error ->
                        if (Velocity.isShuttingDown.get() || !player.isActive) {
                            return@whenComplete
                        }
                        if (error != null) {
                            log.warn("Could not load join message for {}", player.username, error)
                        }
                        sendRegularJoin(player, customMessage)
                    }
            }
        } catch (e: Exception) {
            log.error("Error while sending join message", e)
        }
    }

    private fun leaveMessage(player: Player) {
        if (Velocity.isShuttingDown.get()) return
        loadCustomMessage(player.username, JoinMessages::randomLeaveMessage)
            .whenComplete { customMessage, error ->
                if (Velocity.isShuttingDown.get()) {
                    return@whenComplete
                }
                if (error != null) {
                    log.warn("Could not load leave message for {}", player.username, error)
                }
                sendRegularLeave(player, customMessage)
            }
    }

    private fun loadCustomMessage(
        playerName: String,
        selector: (JoinMessages) -> String?,
    ): CompletableFuture<String?> {
        return JoinMessagesModule.loadAsync(playerName).thenApply { messages ->
            messages?.let(selector)?.replace("%player_name%", playerName)
        }
    }

    private fun sendRegularJoin(
        player: Player,
        customMessage: String?,
    ) {
        Velocity.discordBot?.sendJoinEmbed(player.username, DiscordBot.JoinType.JOIN, customMessage)
        Velocity.telegramBot?.sendJoinMessage(player.username, DiscordBot.JoinType.JOIN, customMessage)
        val message =
            customMessage?.let {
                config.string("messages.join-prefix", "<dark_green>❖ ") + it
            } ?: run {
                val fallback =
                    config.string("messages.join", "<gray>Игрок <green>%player_name% <gray>присоединился!")
                        .replace("%player_name%", player.username)
                config.string("messages.join-prefix", "<dark_green>❖ ") + fallback
            }
        sendMessageToAll(mm(message))
    }

    private fun sendRegularLeave(
        player: Player,
        customMessage: String?,
    ) {
        Velocity.discordBot?.sendJoinEmbed(player.username, DiscordBot.JoinType.LEAVE, customMessage)
        Velocity.telegramBot?.sendJoinMessage(player.username, DiscordBot.JoinType.LEAVE, customMessage)
        val message =
            customMessage?.let {
                config.string("messages.leave-prefix", "<dark_red>❖ ") + it
            } ?: run {
                val fallback =
                    config.string("messages.leave", "<gray>Игрок <red>%player_name% <gray>вышел!")
                        .replace("%player_name%", player.username)
                config.string("messages.leave-prefix", "<dark_red>❖ ") + fallback
            }
        sendMessageToAll(mm(message))
    }

    companion object {
        private val log = LoggerFactory.getLogger(JoinListener::class.java)
    }
}
