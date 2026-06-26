package ru.arc.velocity.listeners

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.LoggerFactory
import ru.arc.ai.AssistantChatBridge
import ru.arc.ai.AssistantChatBridge.InboundSource
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import ru.arc.velocity.Velocity
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class ChatListener(
    private val proxyServer: ProxyServer,
    private val jippityConfig: Config,
) {
    private val mainConfig: Config get() = ProxyConfigs.main()

    private val warnings = ConcurrentHashMap<UUID, ModerationStatus>()

    private data class ModerationStatus(
        val warns: Int,
        val lastWarn: Long,
    )

    @Subscribe(async = true)
    fun onChatMessage(event: PlayerChatEvent) {
        observeChatForAssistant(event)
        aiProcess(event)
        chatProcess(event)
    }

    private fun observeChatForAssistant(event: PlayerChatEvent) {
        if (!event.result.isAllowed) return
        val assistantConfig = ProxyConfigs.module("assistant.yml")
        if (!assistantConfig.bool("chat.enabled", true)) return
        if (!assistantConfig.bool("chat.observe-all-chat", true)) return
        val assistant = ru.arc.velocity.Velocity.chatAssistant ?: return

        val raw = event.message
        val displayMessage = if (raw.startsWith("!")) raw.substring(1) else raw
        val formatted =
            assistantConfig.string("chat.observe-format", "%player% » %message%")
                .replace("%player%", event.player.username)
                .replace("%message%", displayMessage)
        assistant.observeChat(formatted)
    }

    private fun chatProcess(event: PlayerChatEvent) {
        if (!event.result.isAllowed) return
        if (!event.message.startsWith("!")) return
        val username = event.player.username
        val ip = event.player.remoteAddress.address.hostAddress
        val uuid = event.player.uniqueId

        if (Velocity.liteBansHook != null && Velocity.liteBansHook!!.isMuted(uuid, ip)) return

        val message = event.message.substring(1)
        val player = event.player
        val firstJoinTime = Velocity.firstJoinData?.getFirstJoinTime(player.username)
        val minPlayerTime = mainConfig.integer("discord.min-play-time-sec", 600) * 1000L
        if (firstJoinTime == null || firstJoinTime + minPlayerTime > System.currentTimeMillis()) return
        CompletableFuture.runAsync {
            val pattern = mainConfig.string("discord.chat-pattern", "**%player_name%** » %message%")
            var chatMessage = pattern.replace("%player_name%", username).replace("%message%", message)
            Velocity.discordBot?.sendChatMessage(chatMessage)

            val telegramPattern =
                mainConfig.string("telegram.chat-pattern", "\\*\\*%player_name%\\*\\* » %message%")
            chatMessage = telegramPattern.replace("%player_name%", username).replace("%message%", message)
            Velocity.telegramBot?.sendChatMessage(chatMessage)
        }
    }

    private fun aiProcess(event: PlayerChatEvent) {
        if (!event.result.isAllowed) return
        if (!event.message.startsWith("!")) return
        val message = event.message.substring(1)
        val playerName = event.player.username

        AssistantChatBridge.processInbound(
            proxyServer = proxyServer,
            playerName = playerName,
            message = message,
            source = InboundSource.GAME,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChatListener::class.java)
    }
}
