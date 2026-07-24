package ru.arc.ai.routing.survey

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class SurveyResponseHeuristicTest : FreeSpec({
    "accepts short explicit confirmations" - {
        SurveyResponseHeuristic.isShortConfirmation("да") shouldBe true
        SurveyResponseHeuristic.isShortConfirmation("ага, у меня тоже") shouldBe true
        SurveyResponseHeuristic.isShortConfirmation("+1") shouldBe true
        SurveyResponseHeuristic.isShortConfirmation("у меня не работает") shouldBe true
    }

    "rejects unrelated substrings and long chat" - {
        SurveyResponseHeuristic.isShortConfirmation("продам ключ") shouldBe false
        SurveyResponseHeuristic.isShortConfirmation("передай ему привет") shouldBe false
        SurveyResponseHeuristic.isShortConfirmation("sameplace") shouldBe false
        SurveyResponseHeuristic.isShortConfirmation("а".repeat(61)) shouldBe false
    }
})
