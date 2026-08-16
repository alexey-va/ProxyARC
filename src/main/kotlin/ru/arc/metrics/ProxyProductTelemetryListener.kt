package ru.arc.metrics

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.command.CommandExecuteEvent
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import ru.arc.product.ProductCommandClassifier
import ru.arc.product.ProductConnection

class ProxyProductTelemetryListener(
    private val proxy: ProxyServer,
    private val telemetry: ProxyProductTelemetry,
) {
    @Subscribe(order = PostOrder.CUSTOM, priority = Short.MIN_VALUE, async = true)
    fun onCommand(event: CommandExecuteEvent) {
        val player = event.commandSource as? Player ?: return
        val root = ProductCommandClassifier.root(event.command) ?: return
        if (!proxy.commandManager.hasCommand(root)) return
        telemetry.command(player.uniqueId, player.username, event.command)
    }

    @Subscribe(async = true)
    fun onServerConnected(event: ServerConnectedEvent) {
        telemetry.serverConnected(
            event.player.uniqueId,
            event.player.username,
            event.previousServer.map { it.serverInfo.name }.orElse(null),
            event.server.serverInfo.name,
        )
    }

    @Subscribe(order = PostOrder.CUSTOM, priority = Short.MIN_VALUE, async = true)
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        if (event.result.isAllowed) return
        telemetry.connectDenied(
            event.player.uniqueId,
            event.player.username,
            event.originalServer.serverInfo.name,
        )
    }

    @Subscribe(async = true)
    fun onBackendKick(event: KickedFromServerEvent) {
        telemetry.backendKick(
            event.player.uniqueId,
            event.player.username,
            event.server.serverInfo.name,
            event.kickedDuringServerConnect(),
        )
    }

    @Subscribe(async = true)
    fun onDisconnect(event: DisconnectEvent) {
        telemetry.disconnect(
            event.player.uniqueId,
            event.player.username,
            event.loginStatus.connection(),
        )
    }

    private fun DisconnectEvent.LoginStatus.connection(): ProductConnection =
        when (this) {
            DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN -> ProductConnection.DISCONNECT_ACTIVE
            DisconnectEvent.LoginStatus.CONFLICTING_LOGIN -> ProductConnection.LOGIN_CONFLICT
            DisconnectEvent.LoginStatus.CANCELLED_BY_USER -> ProductConnection.LOGIN_CANCELLED_USER
            DisconnectEvent.LoginStatus.CANCELLED_BY_PROXY -> ProductConnection.LOGIN_CANCELLED_PROXY
            DisconnectEvent.LoginStatus.CANCELLED_BY_USER_BEFORE_COMPLETE -> ProductConnection.LOGIN_CANCELLED_EARLY
            DisconnectEvent.LoginStatus.PRE_SERVER_JOIN -> ProductConnection.PRE_SERVER_DISCONNECT
        }
}
