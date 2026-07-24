package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class BugAgentRecoveryPolicyTest : FreeSpec({
    "LLM outage bug recovery" - {
        "asks for details for a real but incomplete report" {
            BugAgentRecoveryPolicy.decide(
                mode = AssistantRunMode.BUG_SURVEY,
                player = "Grocer",
                message = "rtp не работает",
                activeSurveyWithoutTicket = true,
            ) shouldBe BugAgentRecoveryPolicy.Action.ASK_DETAILS
        }

        "creates a ticket when an active survey has concrete command and breakage" {
            BugAgentRecoveryPolicy.decide(
                mode = AssistantRunMode.BUG_SURVEY,
                player = "Grocer",
                message = "на survival /rtp не работает",
                activeSurveyWithoutTicket = true,
            ) shouldBe BugAgentRecoveryPolicy.Action.CREATE_TICKET
        }

        "suppresses opt-out and ordinary chat during outage" {
            BugAgentRecoveryPolicy.decide(
                mode = AssistantRunMode.BUG_SURVEY,
                player = "Grocer",
                message = "скорен отстань",
                activeSurveyWithoutTicket = true,
            ) shouldBe BugAgentRecoveryPolicy.Action.SUPPRESS

            BugAgentRecoveryPolicy.decide(
                mode = AssistantRunMode.CHAT,
                player = "Grocer",
                message = "скорен ты тут",
                activeSurveyWithoutTicket = false,
            ) shouldBe BugAgentRecoveryPolicy.Action.SUPPRESS
        }
    }
})
