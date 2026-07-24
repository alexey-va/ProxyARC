package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.observe.BotReplyTracker
import ru.arc.ai.routing.ingress.MetaBuilder

class MetaBuilderTest : FreeSpec({
    "MetaBuilder" - {
        "detects direct Скорен and bot addresses without third person mentions" {
            val tracker = BotReplyTracker()
            MetaBuilder.directedAtBot("скорен ты тут") shouldBe true
            MetaBuilder.directedAtBot("эй скорен, ты тут") shouldBe true
            MetaBuilder.directedAtBot("как дела скорен") shouldBe true
            MetaBuilder.directedAtBot("спасибо, скорен") shouldBe true
            MetaBuilder.directedAtBot("@скорен help") shouldBe true
            MetaBuilder.directedAtBot("бот а че") shouldBe true
            MetaBuilder.directedAtBot("слушай, бот, ты тут") shouldBe true
            MetaBuilder.directedAtBot("@addscoren help") shouldBe true
            MetaBuilder.directedAtBot("че как все") shouldBe false
            MetaBuilder.directedAtBot("грос сказал что скорен тупой") shouldBe false
            MetaBuilder.directedAtBot("это скорен вчера написал") shouldBe false
            MetaBuilder.directedAtBot("скорен мне вчера с ртп помог") shouldBe false
            MetaBuilder.directedAtBot("кто тут бот вообще") shouldBe false
            MetaBuilder.directedAtBot("я бот для фермы поставил") shouldBe false
            MetaBuilder.directedAtBot("скроен где шахта для новичков") shouldBe true
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

        "tracks consecutive replies to enforce conversation cap" {
            val tracker = BotReplyTracker()
            tracker.record("grocer", nowMs = 1_000L)
            tracker.record("Grocer", nowMs = 2_000L)

            val meta =
                MetaBuilder.build(
                    player = "grocer",
                    message = "а ещё?",
                    botReplyTracker = tracker,
                    continuationWindowSec = 90,
                    timestampMs = 3_000L,
                )

            meta.continuationWithBot shouldBe true
            meta.botRepliesInThread shouldBe 2
        }

        "simulation preserves the live reply count when continuation is explicit" {
            val tracker = BotReplyTracker()
            tracker.record("grocer", nowMs = 1_000L)
            tracker.record("grocer", nowMs = 2_000L)

            val meta =
                MetaBuilder.buildSimulation(
                    player = "grocer",
                    message = "а варпы там тоже есть",
                    botReplyTracker = tracker,
                    continuationWindowSec = 90,
                    timestampMs = 3_000L,
                    replyToBot = false,
                    continuationWithBot = true,
                )

            meta.continuationWithBot shouldBe true
            meta.botRepliesInThread shouldBe 2
        }

        "starts a new reply thread after the continuation window" {
            val tracker = BotReplyTracker()
            tracker.record("grocer", nowMs = 1_000L, threadGapMs = 90_000L)
            tracker.record("grocer", nowMs = 92_000L, threadGapMs = 90_000L)

            tracker.consecutiveRepliesToLastPlayer shouldBe 1
        }
    }
})
