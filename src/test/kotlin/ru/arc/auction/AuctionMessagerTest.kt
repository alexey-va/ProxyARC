package ru.arc.auction

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

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
        messager.map.keys shouldBe setOf(itemId)
    }

    "malformed Redis payload does not escape the channel listener" {
        val messager = AuctionMessager("partial", "all") { null }

        shouldNotThrowAny {
            messager.consume("partial", "{not-json", "spawn")
        }
    }
})
