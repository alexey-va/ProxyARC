package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import ru.arc.ai.routing.survey.BugSurveySessionStore
import ru.arc.ai.tickets.IssueTicketTitles

class BugSurveyActionHintTest : FreeSpec({
    beforeEach { BugSurveySessionStore.clear() }
    afterEach { BugSurveySessionStore.clear() }

    "turnHint" - {
        "menu text in survey requires ticket update" {
            BugSurveySessionStore.openOrTouch("GrocerMC")
            BugSurveySessionStore.bindTicket("GrocerMC", "RB-00004", "rtp")

            val hint =
                BugSurveyActionHint.turnHint(
                    "GrocerMC",
                    "в меню написано скорен лох",
                    "RB-00004",
                )

            hint.shouldNotBeNull()
            hint shouldContain "updateissueticket"
            hint shouldContain "RB-00004"
            hint shouldContain "sendprivatemessage"
        }

        "resolved message closes ticket with closed title prefix" {
            BugSurveySessionStore.openOrTouch("RuneShard")
            BugSurveySessionStore.bindTicket("RuneShard", "RB-00006", "riptide")

            val hint =
                BugSurveyActionHint.turnHint(
                    "RuneShard",
                    "кароч забей нашёл — adventure mode был",
                    "RB-00006",
                )

            hint.shouldNotBeNull()
            hint shouldContain "status=closed"
            hint shouldContain IssueTicketTitles.CLOSED_PREFIX
        }

        "survey detail on classic is update not complete" {
            BugSurveySessionStore.openOrTouch("Dorfik")
            BugSurveySessionStore.bindTicket("Dorfik", "RB-00003", "stick")

            val hint =
                BugSurveyActionHint.turnHint(
                    "Dorfik",
                    "у меня вчера так же было на classic",
                    "RB-00003",
                )

            hint.shouldNotBeNull()
            hint shouldContain "updateissueticket"
            hint shouldNotContain "completebugsurvey"
        }

        "legacy new report hints create without list" {
            val hint = BugSurveyActionHint.turnHint("NovaPlayer", "vote ключ не приходит", null)
            hint.shouldNotBeNull()
            hint shouldContain "createissueticket"
            hint shouldContain "skip listissuetickets"
        }

        "survey detail without ticket yet uses create not list" {
            BugSurveySessionStore.openOrTouch("EchoBolt")
            val hint =
                BugSurveyActionHint.turnHint(
                    "EchoBolt",
                    "/warp shop крашит клиент на classic",
                    null,
                )
            hint.shouldNotBeNull()
            hint shouldContain "createissueticket"
            hint shouldContain "do not listissuetickets"
        }
    }
})
