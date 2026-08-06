package ru.arc.velocity.listeners

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.ProxyServer
import ru.arc.ai.routing.ingress.ChatIngress
import ru.arc.chat.ChatModeService
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import ru.arc.core.Tasks
import ru.arc.logging.ArcLogging
import ru.arc.velocity.Velocity

class ChatListener(
    private val proxyServer: ProxyServer,
) {
    private val mainConfig: Config get() = ProxyConfigs.main()
    private val chatModeProcessor = ChatModeEventProcessor(ChatModeService::getModeNow)

    @Subscribe(order = PostOrder.CUSTOM, priority = Short.MAX_VALUE, async = true)
    fun onChatMessage(event: PlayerChatEvent) {
        val outcome = chatModeProcessor.apply(event)
        ArcLogging.debug(
            "[ChatMode] proxy player={} mode={} allowed={} had-prefix={} prefix-added={}",
            event.player.username,
            outcome.mode,
            event.result.isAllowed,
            outcome.effectiveMessage.startsWith("!"),
            outcome.prefixAdded,
        )
        ChatIngress.onPlayerChat(event, outcome.effectiveMessage)
        chatProcess(event, outcome.effectiveMessage)
    }

    private fun chatProcess(
        event: PlayerChatEvent,
        effectiveMessage: String,
    ) {
        if (!event.result.isAllowed) return
        if (!effectiveMessage.startsWith("!")) return
        val username = event.player.username
        val ip = event.player.remoteAddress.address.hostAddress
        val uuid = event.player.uniqueId

        if (Velocity.liteBansHook?.isMuted(uuid, ip) == true) return

        val message = effectiveMessage.substring(1)
        val player = event.player
        val firstJoinTime = Velocity.firstJoinData?.getFirstJoinTime(player.username)
        val minPlayerTime = mainConfig.integer("discord.min-play-time-sec", 600) * 1000L
        if (firstJoinTime == null || firstJoinTime + minPlayerTime > System.currentTimeMillis()) return
        ArcLogging.debug("[ChatMode] bridge player={} action=forward-global-chat", username)
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
