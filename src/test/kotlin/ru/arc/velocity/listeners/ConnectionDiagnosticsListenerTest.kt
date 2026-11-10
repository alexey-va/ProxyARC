package ru.arc.velocity.listeners

import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.network.ProtocolState
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import io.kotest.core.spec.style.FreeSpec
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.slf4j.Logger
import java.net.InetSocketAddress
import java.util.Optional

class ConnectionDiagnosticsListenerTest : FreeSpec({
    val log = mockk<Logger>(relaxed = true)
    val player = mockk<Player> {
        every { username } returns "CodexQA"
        every { protocolVersion } returns ProtocolVersion.MINECRAFT_1_20_2
        every { currentServer } returns Optional.empty()
    }
    val server = mockk<RegisteredServer> {
        every { serverInfo } returns ServerInfo("spawn", InetSocketAddress.createUnresolved("example.invalid", 25565))
    }

    beforeTest { clearMocks(log) }

    "reports configuration closure even after an earlier successful join" {
        every { player.protocolState } returns ProtocolState.CONFIGURATION
        ConnectionDiagnosticsListener(log) { false }
            .onDisconnect(DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN))

        verify(exactly = 1) {
            log.info(
                "CONNECTION_DIAGNOSTIC event=client_closed player={} client_state={} protocol={} current_backend={} login_status={}",
                "CodexQA", "CONFIGURATION", ProtocolVersion.MINECRAFT_1_20_2.protocol, "none", "SUCCESSFUL_LOGIN",
            )
        }
    }

    "ignores normal play logout and shutdown" {
        every { player.protocolState } returns ProtocolState.PLAY
        ConnectionDiagnosticsListener(log) { false }
            .onDisconnect(DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN))
        every { player.protocolState } returns ProtocolState.CONFIGURATION
        ConnectionDiagnosticsListener(log) { true }
            .onDisconnect(DisconnectEvent(player, DisconnectEvent.LoginStatus.PRE_SERVER_JOIN))

        verify { log wasNot Called }
    }

    "reports a backend failure without logging its kick text" {
        every { player.protocolState } returns ProtocolState.LOGIN
        val event = KickedFromServerEvent(
            player, server, Component.text("private kick text"), true,
            KickedFromServerEvent.DisconnectPlayer.create(Component.text("private disconnect text")),
        )
        ConnectionDiagnosticsListener(log) { false }.onBackendFailure(event)

        verify(exactly = 1) {
            log.info(
                "CONNECTION_DIAGNOSTIC event=backend_failure player={} client_state={} protocol={} backend={} during_connect={} evidence={} action={}",
                "CodexQA", "LOGIN", ProtocolVersion.MINECRAFT_1_20_2.protocol, "spawn", true, "kick_reason_present", "DisconnectPlayer",
            )
        }
    }

    "reports a reasonless backend failure during play" {
        every { player.protocolState } returns ProtocolState.PLAY
        val event = KickedFromServerEvent(
            player, server, null, false,
            KickedFromServerEvent.DisconnectPlayer.create(Component.empty()),
        )
        ConnectionDiagnosticsListener(log) { false }.onBackendFailure(event)

        verify(exactly = 1) {
            log.info(
                "CONNECTION_DIAGNOSTIC event=backend_failure player={} client_state={} protocol={} backend={} during_connect={} evidence={} action={}",
                "CodexQA", "PLAY", ProtocolVersion.MINECRAFT_1_20_2.protocol, "spawn", false, "no_backend_reason", "DisconnectPlayer",
            )
        }
    }
})
