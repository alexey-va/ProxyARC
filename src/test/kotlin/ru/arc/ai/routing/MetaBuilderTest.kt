package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.observe.BotReplyTracker
import ru.arc.ai.routing.ingress.MetaBuilder

class MetaBuilderTest : FreeSpec({
    "MetaBuilder" - {
        "detects skorin and bot" {
            val tracker = BotReplyTracker()
            MetaBuilder.directedAtBot("скорен ты тут") shouldBe true
            MetaBuilder.directedAtBot("бот а че") shouldBe true
            MetaBuilder.directedAtBot("@addscoren help") shouldBe true
            MetaBuilder.directedAtBot("че как все") shouldBe false
        }

        "continuation within window" {
            val tracker = BotReplyTracker()
            tracker.record("grocer")
            val meta =
                MetaBuilder.build(
                    player = "grocer",
                    message = "а почему",
                    botReplyTracker = tracker,
                    continuationWindowSec = 90,
                )
            meta.continuationWithBot shouldBe true
            meta.replyToBot shouldBe true
        }

        "no continuation for other player" {
            val tracker = BotReplyTracker()
            tracker.record("gros")
            val meta =
                MetaBuilder.build(
                    player = "grocer",
                    message = "грос ты где",
                    botReplyTracker = tracker,
                    continuationWindowSec = 90,
                    replyToPlayer = "gros",
                )
            meta.continuationWithBot shouldBe false
            meta.directedAtBot shouldBe false
        }
    }
})
