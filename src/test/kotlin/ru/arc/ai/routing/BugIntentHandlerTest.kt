package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import ru.arc.ai.routing.dispatch.DispatchServices
import ru.arc.ai.routing.dispatch.assistant.AssistantAgentDispatch
import ru.arc.ai.routing.dispatch.assistant.BugSurveyAgentDispatch
import ru.arc.ai.routing.dispatch.handlers.BugIntentHandler
import ru.arc.ai.routing.history.RouteHistoryStore
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.pipeline.PipelineContext
import ru.arc.ai.routing.router.RouteDecision
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouterConfig
import ru.arc.ai.routing.survey.BugSurveySessionStore
import ru.arc.config.Config
import java.nio.file.Files

class BugIntentHandlerTest : FreeSpec({
    beforeEach { BugSurveySessionStore.clear() }
    afterEach { BugSurveySessionStore.clear() }

    "observe-only does not start an agent or survey even on direct bug dispatch" {
        val tmp = Files.createTempDirectory("bug-observe-only-handler")
        Files.writeString(
            tmp.resolve("assistant.yml"),
            """
            bug:
              enabled: true
              observe-only: true
              survey:
                enabled: true
            """.trimIndent(),
        )
        val config = Config(tmp, "assistant.yml")
        val routerConfig = RouterConfig.from(config)
        val surveyDispatch = mockk<BugSurveyAgentDispatch>(relaxed = true)
        val legacyAgent = mockk<AssistantAgentDispatch>(relaxed = true)
        val handler = BugIntentHandler(surveyDispatch, legacyAgent)
        val context =
            PipelineContext(
                message =
                    InboundMessage(
                        player = "Tester",
                        rawText = "rtp не работает",
                        displayText = "rtp не работает",
                        timestampMs = 1L,
                        server = "survival",
                        source = InboundMessage.Source.GAME,
                    ),
                meta = InboundMeta(false, false, false, null, null),
                decision = RouteDecision(RouteIntent.BUG, 0.98, "prefilter:clear_bug", ""),
            )

        handler.dispatch(
            context,
            DispatchServices(
                proxyServer = mockk(relaxed = true),
                routerConfig = routerConfig,
                assistantConfig = config,
                routeHistory = RouteHistoryStore(routerConfig.maxRouteHistory),
            ),
        )

        BugSurveySessionStore.activeCount() shouldBe 0
        verify { surveyDispatch wasNot Called }
        verify { legacyAgent wasNot Called }
    }
})
