package ru.arc.velocity.listeners

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.ProxyServer
import ru.arc.ai.routing.ingress.ChatIngress
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import ru.arc.core.Tasks
import ru.arc.velocity.Velocity

class ChatListener(
    private val proxyServer: ProxyServer,
) {
    private val mainConfig: Config get() = ProxyConfigs.main()

    @Subscribe(async = true)
    fun onChatMessage(event: PlayerChatEvent) {
        ChatIngress.onPlayerChat(event)
        chatProcess(event)
    }

    private fun chatProcess(event: PlayerChatEvent) {
        if (!event.result.isAllowed) return
        if (!event.message.startsWith("!")) return
        val username = event.player.username
        val ip = event.player.remoteAddress.address.hostAddress
        val uuid = event.player.uniqueId

        if (Velocity.liteBansHook?.isMuted(uuid, ip) == true) return

        val message = event.message.substring(1)
        val player = event.player
        val firstJoinTime = Velocity.firstJoinData?.getFirstJoinTime(player.username)
        val minPlayerTime = mainConfig.integer("discord.min-play-time-sec", 600) * 1000L
        if (firstJoinTime == null || firstJoinTime + minPlayerTime > System.currentTimeMillis()) return
        Tasks.scheduler.runAsync {
            val pattern = mainConfig.string("discord.chat-pattern", "**%player_name%** » %message%")
            var chatMessage = pattern.replace("%player_name%", username).replace("%message%", message)
            Velocity.discordBot?.sendChatMessage(chatMessage)

            val telegramPattern =
                mainConfig.string("telegram.chat-pattern", "\\*\\*%player_name%\\*\\* » %message%")
            chatMessage = telegramPattern.replace("%player_name%", username).replace("%message%", message)
            Velocity.telegramBot?.sendChatMessage(chatMessage)
        }
    }
}
