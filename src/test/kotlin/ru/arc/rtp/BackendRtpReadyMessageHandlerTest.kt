package ru.arc.rtp

import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.server.ServerInfo
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.InetSocketAddress
import java.util.UUID

class BackendRtpReadyMessageHandlerTest :
    FreeSpec({
        "marks a valid carrier ready on its backend" {
            val player = mockk<Player>()
            val source = mockk<ServerConnection>()
            val event = mockk<PluginMessageEvent>(relaxed = true)
            val playerId = UUID.randomUUID()
            val ready = mutableListOf<ServerConnection>()

            every { player.uniqueId } returns playerId
            every { source.player } returns player
            every { source.serverInfo } returns ServerInfo("survival", InetSocketAddress("127.0.0.1", 25565))
            every { event.identifier } returns BackendRtpReadyMessageHandler.CHANNEL
            every { event.source } returns source
            every { event.target } returns player
            every { event.data } returns BackendRtpReady(playerId).encode()

            BackendRtpReadyMessageHandler { ready += it }
                .onPluginMessage(event)

            ready shouldBe listOf(source)
            verify { event.result = PluginMessageEvent.ForwardResult.handled() }
        }

        "consumes but rejects client-originated ready signals" {
            val client = mockk<Player>()
            val event = mockk<PluginMessageEvent>(relaxed = true)
            var calls = 0

            every { event.identifier } returns BackendRtpReadyMessageHandler.CHANNEL
            every { event.source } returns client

            BackendRtpReadyMessageHandler { calls++ }.onPluginMessage(event)

            calls shouldBe 0
            verify { event.result = PluginMessageEvent.ForwardResult.handled() }
        }

        "rejects an encoded player mismatch" {
            val carrier = mockk<Player>()
            val source = mockk<ServerConnection>()
            val event = mockk<PluginMessageEvent>(relaxed = true)
            val carrierId = UUID.randomUUID()
            var calls = 0

            every { carrier.uniqueId } returns carrierId
            every { carrier.username } returns "Carrier"
            every { source.player } returns carrier
            every { event.identifier } returns BackendRtpReadyMessageHandler.CHANNEL
            every { event.source } returns source
            every { event.target } returns carrier
            every { event.data } returns BackendRtpReady(UUID.randomUUID()).encode()

            BackendRtpReadyMessageHandler { calls++ }.onPluginMessage(event)

            calls shouldBe 0
        }

        "rejects a target player mismatch" {
            val carrier = mockk<Player>()
            val target = mockk<Player>()
            val source = mockk<ServerConnection>()
            val event = mockk<PluginMessageEvent>(relaxed = true)
            val carrierId = UUID.randomUUID()
            var calls = 0

            every { carrier.uniqueId } returns carrierId
            every { carrier.username } returns "Carrier"
            every { target.uniqueId } returns UUID.randomUUID()
            every { source.player } returns carrier
            every { event.identifier } returns BackendRtpReadyMessageHandler.CHANNEL
            every { event.source } returns source
            every { event.target } returns target
            every { event.data } returns BackendRtpReady(carrierId).encode()

            BackendRtpReadyMessageHandler { calls++ }.onPluginMessage(event)

            calls shouldBe 0
        }
    })
