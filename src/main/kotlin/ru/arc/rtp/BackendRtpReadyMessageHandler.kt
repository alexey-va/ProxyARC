package ru.arc.rtp

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import org.slf4j.LoggerFactory

class BackendRtpReadyMessageHandler(
    private val markReady: (ServerConnection) -> Unit,
) {
    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != CHANNEL) return
        event.result = PluginMessageEvent.ForwardResult.handled()

        val source = event.source as? ServerConnection ?: return
        val carrier = source.player
        val target =
            event.target as? Player
                ?: run {
                    log.warn("Rejected backend RTP ready signal from {} without a player target", carrier.username)
                    return
                }
        if (target.uniqueId != carrier.uniqueId) {
            log.warn(
                "Rejected backend RTP ready signal from {} because target player does not match the carrier",
                carrier.username,
            )
            return
        }

        val ready =
            runCatching { BackendRtpReady.decode(event.data) }
                .getOrElse { failure ->
                    log.warn(
                        "Rejected malformed backend RTP ready signal from {}: {}",
                        carrier.username,
                        failure.message,
                    )
                    return
                }
        if (ready.playerId != carrier.uniqueId) {
            log.warn(
                "Rejected backend RTP ready signal from {} because encoded player does not match the carrier",
                carrier.username,
            )
            return
        }
        markReady(source)
    }

    companion object {
        val CHANNEL: MinecraftChannelIdentifier =
            MinecraftChannelIdentifier.from(BackendRtpReady.CHANNEL)
        private val log = LoggerFactory.getLogger(BackendRtpReadyMessageHandler::class.java)
    }
}
