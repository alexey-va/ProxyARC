package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class AssistantQueuePolicyTest : FreeSpec({
    "chat coalescing" - {
        "new chat from same player supersedes only their queued chat" {
            AssistantQueuePolicy.shouldSupersede(
                queuedPlayer = "Grocer",
                queuedMode = AssistantRunMode.CHAT,
                incomingPlayer = "grocer",
                incomingMode = AssistantRunMode.CHAT,
            ) shouldBe true

            AssistantQueuePolicy.shouldSupersede(
                queuedPlayer = "Other",
                queuedMode = AssistantRunMode.CHAT,
                incomingPlayer = "grocer",
                incomingMode = AssistantRunMode.CHAT,
            ) shouldBe false

            AssistantQueuePolicy.shouldSupersede(
                queuedPlayer = "Grocer",
                queuedMode = AssistantRunMode.BUG_SURVEY,
                incomingPlayer = "grocer",
                incomingMode = AssistantRunMode.BUG_SURVEY,
            ) shouldBe false
        }
    }

    "queue expiry" - {
        "expires at configured age boundary" {
            AssistantQueuePolicy.isExpired(1_000L, 15_999L, 15_000L) shouldBe false
            AssistantQueuePolicy.isExpired(1_000L, 16_000L, 15_000L) shouldBe true
        }
    }
})
