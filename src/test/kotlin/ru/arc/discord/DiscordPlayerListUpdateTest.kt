package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class DiscordPlayerListUpdateTest : FreeSpec({
    "playerListSignature" - {
        "should ignore player order" {
            DiscordBot.playerListSignature(listOf("b", "a", "c")) shouldBe "a\nb\nc"
        }

        "should change when roster changes" {
            val before = DiscordBot.playerListSignature(listOf("a", "b"))
            val after = DiscordBot.playerListSignature(listOf("a", "b", "c"))
            before shouldBe "a\nb"
            (before != after) shouldBe true
        }
    }

    "shouldUpdatePlayerList" - {
        val heartbeatMs = 10 * 60 * 1000L

        "should publish when roster changed" {
            DiscordBot.shouldUpdatePlayerList("a\nb", "a", 0, 1_000, heartbeatMs) shouldBe true
        }

        "should skip when unchanged and heartbeat not due" {
            DiscordBot.shouldUpdatePlayerList("a\nb", "a\nb", 1_000, 60_000, heartbeatMs) shouldBe false
        }

        "should publish heartbeat when unchanged but interval elapsed" {
            DiscordBot.shouldUpdatePlayerList("a\nb", "a\nb", 0, heartbeatMs + 1, heartbeatMs) shouldBe true
        }

        "should publish on first sync" {
            DiscordBot.shouldUpdatePlayerList("a", null, 0, 1, heartbeatMs) shouldBe true
        }
    }

    "status updates are single-flight" - {
        "should retain only the latest snapshot while Discord edit is pending" {
            val gate = DiscordStatusPublishGate<String>()

            gate.offer("spawn") shouldBe "spawn"
            gate.offer("survival") shouldBe null
            gate.offer("parkour") shouldBe null

            gate.complete() shouldBe "parkour"
            gate.offer("end") shouldBe null
            gate.complete() shouldBe "end"
            gate.complete() shouldBe null
            gate.offer("parkour") shouldBe "parkour"
        }

        "should release the slot when there is no pending snapshot" {
            val gate = DiscordStatusPublishGate<String>()

            gate.offer("spawn") shouldBe "spawn"
            gate.complete() shouldBe null
            gate.offer("survival") shouldBe "survival"
        }

        "should discard stale pending state after a failed request" {
            val gate = DiscordStatusPublishGate<String>()

            gate.offer("spawn") shouldBe "spawn"
            gate.offer("survival") shouldBe null
            gate.abandon()

            gate.offer("parkour") shouldBe "parkour"
        }
    }
})
