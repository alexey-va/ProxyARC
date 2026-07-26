package ru.arc.ai.routing.ingress

import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import ru.arc.ai.routing.RoutingModule
import ru.arc.ai.routing.pipeline.PipelineContext
import ru.arc.config.ProxyConfigs

object ChatIngress {
    fun onPlayerChat(event: PlayerChatEvent) {
        if (!event.result.isAllowed) return
        val pipeline = RoutingModule.pipeline ?: return

        RoutingModule.sweepSurveyTimeouts()

        val player = event.player
        val raw = event.message
        val displayText = if (raw.startsWith("!")) raw.substring(1) else raw
        if (displayText.isBlank()) return

        val message =
            InboundMessage(
                player = player.username,
                rawText = raw,
                displayText = displayText,
                timestampMs = System.currentTimeMillis(),
                server = resolveServer(player),
                source = InboundMessage.Source.GAME,
            )
        val meta =
            MetaBuilder.build(
                player = player.username,
                message = displayText,
                botReplyTracker = RoutingModule.botReplyTracker,
                continuationWindowSec = RoutingModule.continuationWindowSec,
                timestampMs = message.timestampMs,
            )
        pipeline.run(PipelineContext(message = message, meta = meta))
    }

    /**
     * Test/dev entry: run full router pipeline without in-game chat event.
     * Used by ProxyARC ops HTTP (`POST /ops/assistant/simulate`).
     */
    fun simulateGameChat(
        player: String,
        message: String,
        rawText: String? = null,
        server: String? = "survival",
        replyToBot: Boolean = false,
        continuationWithBot: Boolean = false,
        waitSeconds: Int = 45,
        previewOnly: Boolean = false,
    ): AssistantSimulateResult {
        val pipeline = RoutingModule.pipeline
            ?: return AssistantSimulateResult.error(player, message, "pipeline not ready")

        RoutingModule.sweepSurveyTimeouts()

        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            return AssistantSimulateResult.error(player, message, "message is blank")
        }

        val raw = rawText?.trim()?.takeIf { it.isNotEmpty() } ?: trimmed
        val displayText = if (raw.startsWith("!")) raw.substring(1) else raw
        if (displayText.isBlank()) {
            return AssistantSimulateResult.error(player, message, "display text is blank")
        }

        val inbound =
            InboundMessage(
                player = player.trim(),
                rawText = raw,
                displayText = displayText,
                timestampMs = System.currentTimeMillis(),
                server = server?.trim()?.takeIf { it.isNotEmpty() },
                source = InboundMessage.Source.SIMULATION,
            )
        val meta =
            MetaBuilder.buildSimulation(
                player = inbound.player,
                message = displayText,
                botReplyTracker = RoutingModule.botReplyTracker,
                continuationWindowSec = RoutingModule.continuationWindowSec,
                timestampMs = inbound.timestampMs,
                replyToBot = replyToBot,
                continuationWithBot = continuationWithBot,
            )

        if (previewOnly) {
            val decision = pipeline.preview(PipelineContext(message = inbound, meta = meta))
            return if (decision == null) {
                AssistantSimulateResult(
                    player = player,
                    message = displayText,
                    intent = "llm_required",
                    reason = "prefilter:ambiguous",
                    confidence = 0.0,
                    parseOk = true,
                    agentWait = "preview:not_dispatched",
                )
            } else {
                AssistantSimulateResult(
                    player = player,
                    message = displayText,
                    intent = decision.intent.wireName(),
                    reason = decision.reason,
                    confidence = decision.confidence,
                    parseOk = decision.parseOk,
                    agentWait = "preview:not_dispatched",
                )
            }
        }

        val routed =
            try {
                pipeline.runAndAwait(
                    PipelineContext(message = inbound, meta = meta),
                    waitSeconds.toLong().coerceIn(5L, 90L),
                )
            } catch (e: Exception) {
                return AssistantSimulateResult.error(player, message, "pipeline: ${e.message}")
            }

        val decision = routed.decision
            ?: return AssistantSimulateResult.error(player, message, "no route decision")

        val agentWaitSec = (waitSeconds.toLong() * 2).coerceIn(15L, 120L)
        val agentWait = pipeline.awaitAgents(decision.intent, agentWaitSec)
        return AssistantSimulateResult(
            player = player,
            message = displayText,
            intent = decision.intent.wireName(),
            reason = decision.reason,
            confidence = decision.confidence,
            parseOk = decision.parseOk,
            agentWait = agentWait,
        )
    }

    fun onDiscordInbound(
        proxyServer: ProxyServer,
        playerName: String,
        messageText: String,
        replyToBot: Boolean,
        replyToPlayer: String?,
    ) {
        val assistantConfig = ProxyConfigs.module("assistant.yml")
        if (!assistantConfig.bool("chat.discord-inbound", true)) return
        val pipeline = RoutingModule.pipeline ?: return
        RoutingModule.sweepSurveyTimeouts()
        if (messageText.isBlank()) return

        val message =
            InboundMessage(
                player = playerName,
                rawText = messageText,
                displayText = messageText,
                timestampMs = System.currentTimeMillis(),
                server = null,
                source = InboundMessage.Source.DISCORD,
            )
        val meta =
            MetaBuilder.buildDiscord(
                player = playerName,
                message = messageText,
                botReplyTracker = RoutingModule.botReplyTracker,
                continuationWindowSec = RoutingModule.continuationWindowSec,
                replyToBot = replyToBot,
                replyToPlayer = replyToPlayer,
                timestampMs = message.timestampMs,
            )
        pipeline.run(PipelineContext(message = message, meta = meta))
    }

    private fun resolveServer(player: Player): String? =
        player.currentServer.map { it.serverInfo.name }.orElse(null)
}
