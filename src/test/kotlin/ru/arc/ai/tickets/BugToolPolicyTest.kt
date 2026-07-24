package ru.arc.ai.tickets

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.survey.BugSurveySessionStore
import ru.arc.ai.tickets.IssueTicket
import ru.arc.ai.tickets.IssueTicketStore

class BugToolPolicyTest : FreeSpec({
    beforeEach {
        BugSurveySessionStore.clear()
        IssueTicketStore.bind(null)
    }
    afterEach {
        BugSurveySessionStore.clear()
        IssueTicketStore.bind(null)
    }

    "shouldOfferListIssueTickets is false without open ticket" {
        BugToolPolicy.shouldOfferListIssueTickets("NobodyHere") shouldBe false
    }

    "shouldOfferListIssueTickets is false when survey already bound ticket" {
        BugSurveySessionStore.openOrTouch("IronVeil")
        BugSurveySessionStore.bindTicket("IronVeil", "RB-00017", "/grave dup")
        BugToolPolicy.shouldOfferListIssueTickets("IronVeil") shouldBe false
    }

    "shouldOfferListIssueTickets is true with open ticket and no bound survey" {
        IssueTicketStore.save(
            IssueTicket(
                ticketId = "RB-00042",
                threadId = "t1",
                starterMessageId = "m1",
                reporter = "SageWire",
                title = "test",
                createdAt = 1L,
            ),
        )
        BugToolPolicy.shouldOfferListIssueTickets("SageWire") shouldBe true
    }
})
