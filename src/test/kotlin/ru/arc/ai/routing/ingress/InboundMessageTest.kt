package ru.arc.ai.routing.ingress

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class InboundMessageTest : FreeSpec({
    "source wire names distinguish production and synthetic traffic" {
        InboundMessage.Source.GAME.wireName() shouldBe "game"
        InboundMessage.Source.DISCORD.wireName() shouldBe "discord"
        InboundMessage.Source.SIMULATION.wireName() shouldBe "simulation"
    }

    "simulation keeps game routing semantics" {
        InboundMessage(
            player = "QA",
            rawText = "!скорен",
            displayText = "скорен",
            timestampMs = 1,
            server = "survival",
            source = InboundMessage.Source.SIMULATION,
        ).allowsChatRouting() shouldBe true
    }
})
