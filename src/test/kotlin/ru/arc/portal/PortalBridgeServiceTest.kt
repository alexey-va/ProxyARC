package ru.arc.portal

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.slf4j.Logger
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture

class PortalBridgeServiceTest : FreeSpec({
    "chat presence and identity snapshots use the authenticated bounded portal contract" {
        val requests = mutableListOf<RecordedRequest>()
        val transport = PortalHttpTransport { endpoint, token, body, _ ->
            requests += RecordedRequest(endpoint, token, body)
            CompletableFuture.completedFuture(201)
        }
        val service =
            PortalBridgeService(
                config = TestPortalBridgeConfig(),
                logger = mockk<Logger>(relaxed = true),
                transport = transport,
            )
        val playerId = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")

        service.publishChat(
            PortalChatMessage(
                sourceEventId = "minecraft:event-1",
                source = PortalChatSource.MINECRAFT,
                channel = PortalChatChannel.GAME,
                authorUuid = playerId,
                authorName = "Explorer",
                content = "Всем привет!",
                createdAt = 1_800_000_000_000,
            ),
        ) shouldBe true
        service.publishPresence(
            capturedAt = 1_800_000_060_000,
            players = listOf(PortalPresencePlayer(playerId, "Explorer", "survival")),
        ) shouldBe true
        service.publishIdentitySnapshot(
            provider = PortalIdentityProvider.DISCORD,
            capturedAt = 1_800_000_060_000,
            identities =
                listOf(
                    PortalExternalIdentity(
                        providerUserId = "900000000000000001",
                        minecraftUuid = playerId,
                        minecraftName = "Explorer",
                        linkedAt = 1_700_000_000_000,
                        updatedAt = 1_800_000_000_000,
                    ),
                ),
        ) shouldBe true

        requests.size shouldBe 3
        requests.map { it.endpoint.path } shouldBe
            listOf(
                "/api/v1/integrations/chat",
                "/api/v1/integrations/presence",
                "/api/v1/integrations/identities",
            )
        requests.all { it.token == "test-bridge-token-that-is-long-enough" } shouldBe true
        val chat = JsonParser.parseString(requests[0].body).asJsonObject
        chat["source"].asString shouldBe "minecraft"
        chat["channel"].asString shouldBe "game"
        chat["authorUuid"].asString shouldBe playerId.toString()
        val presence = JsonParser.parseString(requests[1].body).asJsonObject
        presence["players"].asJsonArray.single().asJsonObject["server"].asString shouldBe "survival"
        val identities = JsonParser.parseString(requests[2].body).asJsonObject
        identities["provider"].asString shouldBe "discord"
        identities["identities"].asJsonArray.single().asJsonObject["providerUserId"].asString shouldBe
            "900000000000000001"
        service.inFlightCount() shouldBe 0
    }

    "saturation drops only the portal copy" {
        val pending = CompletableFuture<Int>()
        val service =
            PortalBridgeService(
                config = TestPortalBridgeConfig(maxInFlight = 1),
                logger = mockk<Logger>(relaxed = true),
                transport = PortalHttpTransport { _, _, _, _ -> pending },
            )
        val message =
            PortalChatMessage(
                sourceEventId = "discord:1",
                source = PortalChatSource.DISCORD,
                channel = PortalChatChannel.COMMUNITY,
                authorUuid = null,
                authorName = "Builder",
                content = "Новый спавн",
                createdAt = 1,
            )

        service.publishChat(message) shouldBe true
        service.publishChat(message.copy(sourceEventId = "discord:2")) shouldBe false
        pending.complete(201)
        service.inFlightCount() shouldBe 0
    }

    "outbound chat is validated delivered and acknowledged without overlapping polls" {
        val posts = mutableListOf<RecordedRequest>()
        val gets = mutableListOf<RecordedGet>()
        val deliveries = mutableListOf<PortalOutboundChatMessage>()
        val getTransport = PortalHttpGetTransport { endpoint, token, _ ->
            gets += RecordedGet(endpoint, token)
            CompletableFuture.completedFuture(
                PortalHttpGetResponse(
                    200,
                    """{"messages":[{"id":7,"sourceEventId":"website:event-7","channel":"game","authorUuid":"01234567-89ab-cdef-0123-456789abcdef","authorName":"Explorer","content":"Сообщение с сайта","createdAt":1800000000000}]}""",
                ),
            )
        }
        val service =
            PortalBridgeService(
                config = TestPortalBridgeConfig(),
                logger = mockk<Logger>(relaxed = true),
                transport = PortalHttpTransport { endpoint, token, body, _ ->
                    posts += RecordedRequest(endpoint, token, body)
                    CompletableFuture.completedFuture(204)
                },
                getTransport = getTransport,
            )

        service.pollOutboundChat { message ->
            deliveries += message
            true
        } shouldBe PortalOutboundPollStart.STARTED

        gets.single().endpoint.path shouldBe "/api/v1/integrations/chat/outbox"
        gets.single().token shouldBe "test-bridge-token-that-is-long-enough"
        deliveries.single().authorName shouldBe "Explorer"
        deliveries.single().content shouldBe "Сообщение с сайта"
        posts.single().endpoint.path shouldBe "/api/v1/integrations/chat/outbox/7/ack"
        posts.single().token shouldBe "test-bridge-token-that-is-long-enough"
    }

    "close prevents a pending outbound response from reaching chat sinks" {
        val response = CompletableFuture<PortalHttpGetResponse>()
        var deliveries = 0
        val service =
            PortalBridgeService(
                config = TestPortalBridgeConfig(),
                logger = mockk<Logger>(relaxed = true),
                transport = PortalHttpTransport { _, _, _, _ -> CompletableFuture.completedFuture(204) },
                getTransport = PortalHttpGetTransport { _, _, _ -> response },
            )

        service.pollOutboundChat { deliveries += 1; true } shouldBe PortalOutboundPollStart.STARTED
        service.pollOutboundChat { true } shouldBe PortalOutboundPollStart.ALREADY_RUNNING
        service.close()
        response.complete(PortalHttpGetResponse(200, """{"messages":[]}"""))

        deliveries shouldBe 0
        service.pollOutboundChat { true } shouldBe PortalOutboundPollStart.CLOSED
    }

    "synchronous outbound transport failure is reported without locking future polls" {
        val service =
            PortalBridgeService(
                config = TestPortalBridgeConfig(),
                logger = mockk<Logger>(relaxed = true),
                transport = PortalHttpTransport { _, _, _, _ -> CompletableFuture.completedFuture(204) },
                getTransport = PortalHttpGetTransport { _, _, _ -> error("transport unavailable") },
            )

        service.pollOutboundChat { true } shouldBe PortalOutboundPollStart.FAILED_TO_START
        service.pollOutboundChat { true } shouldBe PortalOutboundPollStart.FAILED_TO_START
    }

    "oversized outbound responses are rejected before delivery" {
        var deliveries = 0
        val service =
            PortalBridgeService(
                config = TestPortalBridgeConfig(),
                logger = mockk<Logger>(relaxed = true),
                transport = PortalHttpTransport { _, _, _, _ -> CompletableFuture.completedFuture(204) },
                getTransport =
                    PortalHttpGetTransport { _, _, _ ->
                        CompletableFuture.completedFuture(PortalHttpGetResponse(200, "x".repeat(64 * 1024 + 1)))
                    },
            )

        service.pollOutboundChat { deliveries += 1; true } shouldBe PortalOutboundPollStart.STARTED
        deliveries shouldBe 0
        service.pollOutboundChat { true } shouldBe PortalOutboundPollStart.STARTED
    }
})

private data class RecordedRequest(
    val endpoint: URI,
    val token: String,
    val body: String,
)

private data class RecordedGet(
    val endpoint: URI,
    val token: String,
)
