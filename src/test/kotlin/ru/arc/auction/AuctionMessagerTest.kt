package ru.arc.auction

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

private fun item(id: UUID, display: String): String =
    item(id.toString(), display)

private fun item(id: String, display: String): String =
    """[{"uuid":"$id","display":"$display","priority":1,"exist":true}]"""

class AuctionMessagerTest : FreeSpec({
    "resolves Discord bot when an update arrives instead of capturing startup state" {
        var providerCalls = 0
        val messager =
            AuctionMessager("partial", "all") {
                providerCalls++
                null
            }
        val itemId = UUID.randomUUID()
        val update = """[{"uuid":"$itemId","display":"Stone","priority":1,"exist":true}]"""

        messager.consume("partial", update, "spawn")
        messager.consume("partial", update, "spawn")

        providerCalls shouldBe 2
        messager.map.keys shouldBe setOf(itemId.toString())
    }

    "malformed Redis payload does not escape the channel listener" {
        val messager = AuctionMessager("partial", "all") { null }

        shouldNotThrowAny {
            messager.consume("partial", "{not-json", "spawn")
        }
    }

    "full snapshots replace only the publishing server" {
        val messager = AuctionMessager("partial", "all") { null }
        val spawnItem = UUID.randomUUID()
        val survivalItem = UUID.randomUUID()

        messager.consume("all", item(spawnItem, "Stone"), "spawn")
        messager.consume("all", item(survivalItem, "Diamond"), "survival")
        messager.consume("all", "[]", "spawn")

        messager.map.keys shouldBe setOf(survivalItem.toString())
    }

    "same listing received from multiple servers is deduplicated" {
        val messager = AuctionMessager("partial", "all") { null }
        val itemId = UUID.randomUUID()

        messager.consume("all", item(itemId, "Stone"), "spawn")
        messager.consume("all", item(itemId, "Stone"), "survival")

        messager.map.keys shouldBe setOf(itemId.toString())
    }

    "malformed full snapshot does not erase the previous server snapshot" {
        val messager = AuctionMessager("partial", "all") { null }
        val itemId = UUID.randomUUID()

        messager.consume("all", item(itemId, "Stone"), "spawn")
        messager.consume("all", "{not-json", "spawn")

        messager.map.keys shouldBe setOf(itemId.toString())
    }

    "numeric zAuctionHouse v4 listing ids are accepted" {
        val messager = AuctionMessager("partial", "all") { null }

        messager.consume("all", item("2", "Stone"), "spawn")

        messager.map.keys shouldBe setOf("2")
    }

    "sale event validation rejects spoofed player names" {
        AuctionMessager.validSale(
            AuctionSaleEventDto(
                listingId = "42",
                sellerName = "@everyone",
                buyerName = "GrocerMC",
                itemDisplay = "Алмаз",
                amount = 1,
                price = "100",
                occurredAt = 1,
            ),
        ) shouldBe false
    }

})
