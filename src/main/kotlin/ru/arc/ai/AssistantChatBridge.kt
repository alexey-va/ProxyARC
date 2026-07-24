package ru.arc.ai

import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.LoggerFactory
import ru.arc.Utils
import ru.arc.ai.routing.RoutingModule
import ru.arc.config.ProxyConfigs
import ru.arc.core.TickConstants
import ru.arc.core.delayed
import ru.arc.velocity.Velocity

object AssistantChatBridge {
    private val log = LoggerFactory.getLogger(AssistantChatBridge::class.java)

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

        val relayDiscord = AssistantChatFormat.relayDiscord(assistantConfig)
        val relayTelegram = AssistantChatFormat.relayTelegram(assistantConfig)
        val delayMs = AssistantChatFormat.multiMessageDelayMs(assistantConfig)
        val assistant = Velocity.chatAssistant

        // Publish conversation state before the scheduler callback. Fast replies
        // and ops E2E calls must see the updated turn count immediately.
        RoutingModule.recordBotReply(triggerPlayer)

        normalized.parts.forEachIndexed { index, part ->
            val delayTicks = TickConstants.millisToTicks(delayMs * index)
            delayed(delayTicks) {
                val inGameText = AssistantChatFormat.inGameMessage(assistantConfig, part)
                val component = Utils.legacy(inGameText)
                proxyServer.allPlayers.forEach { it.sendMessage(component) }

                assistant?.observeChat(
                    RoutingModule.formatBotObserveLine(part, botName),
                )

                if (relayDiscord) {
                    Velocity.discordBot?.sendChatMessage(
                        AssistantChatFormat.discordMessage(mainConfig, botName, part),
                    )
                }
                if (relayTelegram) {
                    Velocity.telegramBot?.sendChatMessage(
                        AssistantChatFormat.telegramMessage(mainConfig, botName, part),
                    )
                }
            }
        }
    }
}
