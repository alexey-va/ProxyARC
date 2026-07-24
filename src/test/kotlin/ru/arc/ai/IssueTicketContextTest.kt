package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class IssueTicketContextTest : FreeSpec({
    "IssueTicketContext" - {
        "displayServer maps velocity names" {
            IssueTicketContext.displayServer("survival") shouldBe "мир биомов"
            IssueTicketContext.displayServer("spawn") shouldBe "спавн"
            IssueTicketContext.displayServer("minecraft-server") shouldBe "прокси"
        }

        "serverFieldValue shows player world only" {
            val ctx =
                IssueTicketContext(
                    reporter = "test",
                    backendServer = "classic_survival",
                    displayServer = "мир биомов",
                    reportedAt = "28.06.2026 12:00 (MSK)",
                    triggerMessage = "лагает",
                    chatSnippet = null,
                    dialogSnippet = null,
                )
            ctx.serverFieldValue() shouldBe "мир биомов"
        }
    }
})
