package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class IssueTicketContextTest : FreeSpec({
    "IssueTicketContext" - {
        "displayServer maps velocity names" {
            IssueTicketContext.displayServer("survival") shouldBe "Survival"
            IssueTicketContext.displayServer("spawn") shouldBe "Спавн"
            IssueTicketContext.displayServer("minecraft-server") shouldBe "Velocity"
        }

        "serverFieldValue shows backend when different from display" {
            val ctx =
                IssueTicketContext(
                    reporter = "test",
                    backendServer = "survival",
                    displayServer = "Survival",
                    reportedAt = "28.06.2026 12:00 (MSK)",
                    triggerMessage = "лагает",
                    chatSnippet = null,
                )
            ctx.serverFieldValue() shouldBe "Survival"
        }
    }
})
