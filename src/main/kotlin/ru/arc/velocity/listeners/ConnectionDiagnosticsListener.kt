package ru.arc.velocity.listeners

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.network.ProtocolState
import net.kyori.adventure.text.TranslatableComponent
import org.slf4j.Logger

/** Adds protocol context to unexpected closures. Velocity logs the underlying transport exception when it has one. */
internal class ConnectionDiagnosticsListener(
    private val log: Logger,
    private val shuttingDown: () -> Boolean,
) {
    @Subscribe(async = true)
    fun onDisconnect(event: DisconnectEvent) {
        if (shuttingDown()) return
        val player = event.player
        val state = player.protocolState
        val status = event.loginStatus
        if (state != ProtocolState.CONFIGURATION &&
            status != DisconnectEvent.LoginStatus.PRE_SERVER_JOIN &&
            status != DisconnectEvent.LoginStatus.CANCELLED_BY_USER_BEFORE_COMPLETE
        ) return
        log.info(
            "CONNECTION_DIAGNOSTIC event=client_closed player={} client_state={} protocol={} current_backend={} login_status={}",
            player.username, state.name, player.protocolVersion.protocol,
            player.currentServer.map { it.serverInfo.name }.orElse("none"), status.name,
        )
    }

    @Subscribe(order = PostOrder.CUSTOM, priority = Short.MIN_VALUE, async = false)
    fun onBackendFailure(event: KickedFromServerEvent) {
        if (shuttingDown()) return
        val player = event.player
        val state = player.protocolState
        val reason = event.serverKickReason.orElse(null)
        if (reason != null && !event.kickedDuringServerConnect() && state != ProtocolState.CONFIGURATION) return
        val evidence = when {
            reason == null -> "no_backend_reason"
            reason is TranslatableComponent && reason.key() == "disconnect.timeout" -> "kick_reason_timeout"
            else -> "kick_reason_present"
        }
        log.info(
            "CONNECTION_DIAGNOSTIC event=backend_failure player={} client_state={} protocol={} backend={} during_connect={} evidence={} action={}",
            player.username, state.name, player.protocolVersion.protocol,
            event.server.serverInfo.name, event.kickedDuringServerConnect(), evidence, event.result.javaClass.simpleName,
        )
    }
}
