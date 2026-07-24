package ru.arc.ai.routing.survey

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.AssistantRunMode

class BugSurveyLifecycleTest : FreeSpec({
    "private message keeps an unfinished survey active" {
        BugSurveyLifecycle.shouldOpenAfterPrivateMessage(surveyCompleted = false) shouldBe true
    }

    "private message cannot reopen a survey completed earlier in the tool chain" {
        BugSurveyLifecycle.shouldOpenAfterPrivateMessage(surveyCompleted = true) shouldBe false
    }

    "late resolution PM cannot reopen an already closed survey" {
        BugSurveyLifecycle.shouldOpenAfterPrivateMessage(
            surveyCompleted = false,
            isResolutionWithoutOpenTicket = true,
        ) shouldBe false
    }

    "explicit resolution without model tools uses deterministic close" {
        BugSurveyLifecycle.shouldResolveWithoutTools(
            mode = AssistantRunMode.BUG_SURVEY,
            hadToolCalls = false,
            message = "уже заработало после перезахода, проблему решил",
        ) shouldBe true
    }

    "deterministic close does not override a successful tool chain" {
        BugSurveyLifecycle.shouldResolveWithoutTools(
            mode = AssistantRunMode.BUG_SURVEY,
            hadToolCalls = true,
            message = "проблему решил",
        ) shouldBe false
    }
})
