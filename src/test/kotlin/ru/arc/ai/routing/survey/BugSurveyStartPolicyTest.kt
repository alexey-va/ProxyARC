package ru.arc.ai.routing.survey

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.tickets.IssueTicket

class BugSurveyStartPolicyTest : FreeSpec({
    beforeEach { BugSurveySessionStore.clear() }
    afterEach { BugSurveySessionStore.clear() }

    "should not start survey for new vague report without open ticket" {
        BugSurveyStartPolicy.shouldStartSurvey(
            surveyEnabled = true,
            player = "GrocerMC",
            message = "у меня баг с rtp",
            openTicket = null,
        ) shouldBe false
    }

    "should start survey when reporter has open ticket" {
        val open =
            IssueTicket(
                ticketId = "RB-00001",
                threadId = "t1",
                starterMessageId = null,
                reporter = "GrocerMC",
                title = "rtp",
                createdAt = 1L,
            )
        BugSurveyStartPolicy.shouldStartSurvey(
            surveyEnabled = true,
            player = "GrocerMC",
            message = "на survival",
            openTicket = open,
        ) shouldBe true
    }

    "should start survey when message mentions ticket id" {
        BugSurveyStartPolicy.shouldStartSurvey(
            surveyEnabled = true,
            player = "GrocerMC",
            message = "про RB-00042 ещё вопрос",
            openTicket = null,
        ) shouldBe true
    }

    "should start survey when session already active" {
        BugSurveySessionStore.openOrTouch("GrocerMC")
        BugSurveyStartPolicy.shouldStartSurvey(
            surveyEnabled = true,
            player = "GrocerMC",
            message = "ещё детали",
            openTicket = null,
            investigation = BugSurveySessionStore.get("GrocerMC"),
        ) shouldBe true
    }

    "should start survey for witness linked to global inquiry" {
        BugSurveySessionStore.markAwaitingGlobalResponses("GrocerMC", "у кого баг?")
        val investigation =
            BugSurveySessionStore.resolveSession(
                player = "Koxae",
                message = "да",
                meta =
                    InboundMeta(
                        directedAtBot = false,
                        replyToBot = true,
                        continuationWithBot = false,
                        secondsSinceBot = 3,
                        replyToPlayer = null,
                    ),
                globalInquiryWindowMs = 300_000L,
            )
        BugSurveyStartPolicy.shouldStartSurvey(
            surveyEnabled = true,
            player = "Koxae",
            message = "да",
            openTicket = null,
            investigation = investigation,
        ) shouldBe true
    }
})
