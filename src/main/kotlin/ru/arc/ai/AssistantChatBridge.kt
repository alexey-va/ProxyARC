package ru.arc.ai

import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.LoggerFactory
import ru.arc.Utils
import ru.arc.config.ProxyConfigs
import ru.arc.core.delayed
import ru.arc.velocity.Velocity

object AssistantChatBridge {
    private val log = LoggerFactory.getLogger(AssistantChatBridge::class.java)

    fun processInbound(
        proxyServer: ProxyServer,
        playerName: String,
        message: String,
        source: InboundSource = InboundSource.GAME,
    ) {
        val assistantConfig = ProxyConfigs.module("assistant.yml")
        if (source == InboundSource.DISCORD && !assistantConfig.bool("chat.discord-inbound", true)) {
            log.debug("Discord inbound disabled, skip assistant for {}", playerName)
            return
        }

        val assistant = Velocity.chatAssistant ?: return

        assistant.addChatMessage(message, playerName)
        assistant.tryEnqueue(triggerPlayer = playerName, triggerMessage = message).thenAccept { result ->
            try {
                if (!result.hasReply) return@thenAccept
                deliverReply(
                    proxyServer = proxyServer,
                    rawReply = result.reply!!,
                    triggerPlayer = playerName,
                    triggerMessage = message,
                    rawModelContent = result.rawModelContent,
                )
            } catch (e: Exception) {
                log.error(
                    "Error while processing assistant response for {} on \"{}\": {}",
                    playerName,
                    message,
                    e.message,
                    e,
                )
            }
        }
    }

    fun deliverReply(
        proxyServer: ProxyServer,
        rawReply: String,
        triggerPlayer: String,
        triggerMessage: String,
        rawModelContent: String?,
    ) {
        val assistantConfig = ProxyConfigs.module("assistant.yml")
        val mainConfig = ProxyConfigs.main()
        val botName = AssistantChatFormat.displayName(assistantConfig)
        val normalized = AssistantChatFormat.normalizeReplyDetail(assistantConfig, rawReply)
        if (!normalized.hasText) {
            log.info(
                "Assistant chat post-filter skip for {} on \"{}\": reason={} raw=\"{}\" model=\"{}\"",
                triggerPlayer,
                triggerMessage,
                normalized.skipReason ?: "unknown",
                rawReply,
                rawModelContent ?: rawReply,
            )
            return
        }
        val trimmed = normalized.text!!

        val relayDiscord = AssistantChatFormat.relayDiscord(assistantConfig)
        val relayTelegram = AssistantChatFormat.relayTelegram(assistantConfig)
        val inGameText = AssistantChatFormat.inGameMessage(assistantConfig, trimmed)

        delayed(0L) {
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
    }

    enum class InboundSource {
        GAME,
        DISCORD,
    }
}
