package ru.arc.rtp

import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

class BackendRtpMessageHandlerTest :
    FreeSpec({
        "dispatches a valid backend request for its carrier player" {
            val player = mockk<Player>()
            val source = mockk<ServerConnection>()
            val event = mockk<PluginMessageEvent>(relaxed = true)
            val playerId = UUID.randomUUID()
            val dispatched = mutableListOf<Pair<Player, BackendRtpRequest>>()

            every { player.uniqueId } returns playerId
            every { source.player } returns player
            every { event.identifier } returns BackendRtpMessageHandler.CHANNEL
            every { event.source } returns source
            every { event.target } returns player
            every { event.data } returns
                BackendRtpRequest
                    .create(playerId, "mining", NetworkRtpMode.FIRST_ENTRY)
                    .encode()

            BackendRtpMessageHandler { carrier, request -> dispatched += carrier to request }
                .onPluginMessage(event)

            dispatched shouldBe
                listOf(
                    player to
                        BackendRtpRequest.create(
                            playerId,
                            "mining",
                            NetworkRtpMode.FIRST_ENTRY,
                        ),
                )
            verify { event.result = PluginMessageEvent.ForwardResult.handled() }
        }

        "consumes but rejects client-originated messages" {
            val client = mockk<Player>()
            val event = mockk<PluginMessageEvent>(relaxed = true)
            var calls = 0

            every { event.identifier } returns BackendRtpMessageHandler.CHANNEL
            every { event.source } returns client

            BackendRtpMessageHandler { _, _ -> calls++ }.onPluginMessage(event)

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
            every { event.identifier } returns BackendRtpMessageHandler.CHANNEL
            every { event.source } returns source
            every { event.target } returns carrier
            every { event.data } returns
                BackendRtpRequest.create(UUID.randomUUID(), "survival").encode()

            BackendRtpMessageHandler { _, _ -> calls++ }.onPluginMessage(event)

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
            every { event.identifier } returns BackendRtpMessageHandler.CHANNEL
            every { event.source } returns source
            every { event.target } returns target
            every { event.data } returns
                BackendRtpRequest.create(carrierId, "survival").encode()

            BackendRtpMessageHandler { _, _ -> calls++ }.onPluginMessage(event)

            calls shouldBe 0
        }

        "rejects a malformed backend payload" {
            val carrier = mockk<Player>()
            val source = mockk<ServerConnection>()
            val event = mockk<PluginMessageEvent>(relaxed = true)
            var calls = 0

            every { carrier.uniqueId } returns UUID.randomUUID()
            every { carrier.username } returns "Carrier"
            every { source.player } returns carrier
            every { event.identifier } returns BackendRtpMessageHandler.CHANNEL
            every { event.source } returns source
            every { event.target } returns carrier
            every { event.data } returns byteArrayOf(1, 2, 3)

            BackendRtpMessageHandler { _, _ -> calls++ }.onPluginMessage(event)

            calls shouldBe 0
        }

        "ignores unrelated plugin-message channels" {
            val event = mockk<PluginMessageEvent>(relaxed = true)
            var calls = 0

            every { event.identifier } returns RtpRequestManager.CHANNEL

            BackendRtpMessageHandler { _, _ -> calls++ }.onPluginMessage(event)

            calls shouldBe 0
            verify(exactly = 0) {
                event.result = PluginMessageEvent.ForwardResult.handled()
            }
        }
    })
