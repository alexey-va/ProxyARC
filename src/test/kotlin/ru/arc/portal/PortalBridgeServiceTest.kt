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

})

private data class RecordedRequest(
    val endpoint: URI,
    val token: String,
    val body: String,
)
