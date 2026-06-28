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
