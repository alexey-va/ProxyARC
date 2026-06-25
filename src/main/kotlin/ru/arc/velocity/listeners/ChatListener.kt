package ru.arc.velocity.listeners

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.LoggerFactory
import ru.arc.Utils
import ru.arc.ai.AssistantChatFormat
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import ru.arc.core.delayed
import ru.arc.velocity.Velocity
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class ChatListener(
    private val proxyServer: ProxyServer,
    private val jippityConfig: Config,
) {
    private val mainConfig: Config get() = ProxyConfigs.main()
    private val assistantConfig: Config get() = ProxyConfigs.module("assistant.yml")

    private val warnings = ConcurrentHashMap<UUID, ModerationStatus>()

    private data class ModerationStatus(
        val warns: Int,
        val lastWarn: Long,
    )

    @Subscribe(async = true)
    fun onChatMessage(event: PlayerChatEvent) {
        aiProcess(event)
        chatProcess(event)
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
        val assistant = Velocity.chatAssistant ?: return

        assistant.addChatMessage(message, playerName)
        assistant.tryEnqueue().thenAccept { response ->
            try {
                log.info("Chat assistant response: {}", response)
                if (response.isEmpty) {
                    log.error("Empty response from chat assistant")
                    return@thenAccept
                }
                deliverAssistantReply(response.get())
            } catch (e: Exception) {
                log.error("Error while processing chat assistant response", e)
            }
        }
    }

    private fun deliverAssistantReply(rawReply: String) {
        val botName = AssistantChatFormat.displayName(assistantConfig)
        val relayDiscord = AssistantChatFormat.relayDiscord(assistantConfig)
        val relayTelegram = AssistantChatFormat.relayTelegram(assistantConfig)
        val chatMessages = rawReply.replace("\n\n", "\n").split("\n")
        var delay = 0
        for (chatMessage in chatMessages) {
            if (chatMessage.equals("пропускаю", ignoreCase = true)) continue
            val trimmed = chatMessage.trim()
            if (trimmed.isEmpty()) continue

            val inGameText = AssistantChatFormat.inGameMessage(assistantConfig, trimmed)
            delayed(delay * 20L) {
                val component = Utils.legacy(inGameText)
                proxyServer.allPlayers.forEach { it.sendMessage(component) }
            }

            if (relayDiscord) {
                Velocity.discordBot?.sendChatMessage(
                    AssistantChatFormat.discordMessage(mainConfig, botName, trimmed),
                )
            }
            if (relayTelegram) {
                Velocity.telegramBot?.sendChatMessage(
                    AssistantChatFormat.telegramMessage(mainConfig, botName, trimmed),
                )
            }
            delay += 4
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChatListener::class.java)
    }
}
