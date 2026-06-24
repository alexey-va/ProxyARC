package ru.arc.velocity.listeners

import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import org.slf4j.LoggerFactory
import ru.arc.CommonCore
import ru.arc.Utils.mm
import ru.arc.config.Config
import ru.arc.discord.DiscordBot
import ru.arc.core.delayed
import ru.arc.velocity.Velocity
import ru.arc.xserver.JoinMessages
import java.util.concurrent.ThreadLocalRandom

class JoinListener(
    private val commonCore: CommonCore,
    private val proxyServer: ProxyServer,
    private val config: Config,
) {

    @Subscribe(async = true)
    fun onPlayerJoin(event: LoginEvent) {
        if (Velocity.isShuttingDown.get()) return
        delayed(20) { joinMessage(event.player) }
        val serverName = event.player.currentServer
            .map { it.serverInfo.name }
            .orElse("")
        commonCore.playerListAnnouncer!!.addPlayer(
            event.player.uniqueId,
            event.player.username,
            serverName,
        )
        val allow = commonCore.antibot!!.processPlayerJoin(
            event.player.username,
            event.player.uniqueId,
            proxyServer.playerCount,
        )
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
        if (!event.player.hasPermission("arc.join-message.leave")) return
        if (Velocity.isShuttingDown.get()) return
        delayed(20) { leaveMessage(event.player) }
        commonCore.playerListAnnouncer!!.removePlayer(event.player.uniqueId)
        commonCore.antibot!!.processPlayerLeave(event.player.uniqueId)
    }

    @Subscribe(async = true)
    fun onChangeServer(event: ServerConnectedEvent) {
        val server = event.server.serverInfo.name
        val username = event.player.username
        commonCore.playerListAnnouncer!!.updatePlayer(event.player.uniqueId, username, server)
    }

    private fun sendMessageToAll(component: Component) {
        proxyServer.allPlayers.forEach { it.sendMessage(component) }
    }

    private fun joinMessage(player: Player) {
        if (Velocity.isShuttingDown.get()) return
        try {
            if (!player.isActive) return
            val firstTime = commonCore.firstJoinData!!.firstTimeJoin(player.username)
            if (firstTime) {
                commonCore.firstJoinData!!.markAsJoined(player.username)
                if (!player.hasPermission("arc.join-message.first")) return
                commonCore.discordBot!!.sendJoinEmbed(player.username, DiscordBot.JoinType.FIRST_TIME, null)
                commonCore.telegramBot!!.sendJoinMessage(player.username, DiscordBot.JoinType.FIRST_TIME, null)
                var message = config.string("messages.first-join", "<gray>Игрок <green>%player_name% <gray>присоединился впервые!")
                message = message.replace("%player_name%", player.username)
                message = config.string("messages.join-prefix", "<dark_green>❖ ") + message
                sendMessageToAll(mm(message))
            } else {
                if (!player.hasPermission("arc.join-message.join")) return
                var messageOverride: String? = null
                if (commonCore.joinMessagesRedisRepo != null) {
                    val join = commonCore.joinMessagesRedisRepo!!.getOrNull(player.username).join()
                    if (join != null && join.joinMessages.isNotEmpty()) {
                        val idx = ThreadLocalRandom.current().nextInt(join.joinMessages.size)
                        var current = 0
                        for (joinMessage in join.joinMessages) {
                            if (current == idx) {
                                messageOverride = joinMessage.replace("%player_name%", player.username)
                                break
                            }
                            current++
                        }
                    }
                }
                commonCore.discordBot!!.sendJoinEmbed(player.username, DiscordBot.JoinType.JOIN, messageOverride)
                commonCore.telegramBot!!.sendJoinMessage(player.username, DiscordBot.JoinType.JOIN, messageOverride)
                if (messageOverride != null) {
                    messageOverride = config.string("messages.join-prefix", "<dark_green>❖ ") + messageOverride
                }
                var message = config.string("messages.join", "<gray>Игрок <green>%player_name% <gray>присоединился!")
                message = message.replace("%player_name%", player.username)
                message = config.string("messages.join-prefix", "<dark_green>❖ ") + message
                sendMessageToAll(if (messageOverride != null) mm(messageOverride) else mm(message))
            }
        } catch (e: Exception) {
            log.error("Error while sending join message", e)
        }
    }

    private fun leaveMessage(player: Player) {
        if (Velocity.isShuttingDown.get()) return
        var messageOverride: String? = null
        if (commonCore.joinMessagesRedisRepo != null) {
            val join = commonCore.joinMessagesRedisRepo!!.getOrNull(player.username).join()
            if (join != null && join.leaveMessages.isNotEmpty()) {
                val idx = ThreadLocalRandom.current().nextInt(join.leaveMessages.size)
                var current = 0
                for (leaveMessage in join.leaveMessages) {
                    if (current == idx) {
                        messageOverride = leaveMessage.replace("%player_name%", player.username)
                        break
                    }
                    current++
                }
            }
        }
        commonCore.discordBot!!.sendJoinEmbed(player.username, DiscordBot.JoinType.LEAVE, messageOverride)
        commonCore.telegramBot!!.sendJoinMessage(player.username, DiscordBot.JoinType.LEAVE, messageOverride)
        if (messageOverride != null) {
            messageOverride = config.string("messages.leave-prefix", "<dark_red>❖ ") + messageOverride
        }
        var message = config.string("messages.leave", "gray>Игрок <red>%player_name% <gray>вышел!")
        message = message.replace("%player_name%", player.username)
        message = config.string("messages.leave-prefix", "<dark_red>❖ ") + message
        sendMessageToAll(if (messageOverride != null) mm(messageOverride) else mm(message))
    }

    companion object {
        private val log = LoggerFactory.getLogger(JoinListener::class.java)
    }
}
