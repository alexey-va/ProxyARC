package ru.arc.ai.routing

import com.velocitypowered.api.proxy.ProxyServer
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.arc.ai.Assistant
import ru.arc.ai.AssistantRunMode
import ru.arc.ai.routing.dispatch.assistant.AssistantAgentDispatch
import ru.arc.velocity.Velocity

class AssistantIsolationTest : FreeSpec({
    "agent selection keeps public chat and bug history isolated" {
        val previousChat = Velocity.chatAssistant
        val previousBug = Velocity.bugSurveyAssistant
        val chat = mockk<Assistant>(relaxed = true)
        val bug = mockk<Assistant>(relaxed = true)

        try {
            Velocity.chatAssistant = chat
            Velocity.bugSurveyAssistant = bug
            val dispatch = AssistantAgentDispatch(mockk<ProxyServer>(relaxed = true))

            dispatch.assistant(AssistantRunMode.CHAT) shouldBe chat
            dispatch.assistant(AssistantRunMode.BUG) shouldBe bug
            dispatch.assistant(AssistantRunMode.BUG_SURVEY) shouldBe bug
        } finally {
            Velocity.chatAssistant = previousChat
            Velocity.bugSurveyAssistant = previousBug
        }
    }
})
