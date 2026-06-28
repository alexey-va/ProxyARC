package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.arc.ai.routing.dispatch.DispatchServices
import ru.arc.ai.routing.dispatch.IntentHandlerRegistry
import ru.arc.ai.routing.dispatch.handlers.SkipIntentHandler
import ru.arc.ai.routing.history.RouteHistoryStore
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.pipeline.PipelineContext
import ru.arc.ai.routing.router.RouteDecision
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouterConfig
import ru.arc.config.Config
import java.nio.file.Files

class IntentHandlerRegistryTest : FreeSpec({
    "IntentHandlerRegistry" - {
        "records route history on dispatch" {
            val tmp = Files.createTempDirectory("intent-registry")
            val config = Config(tmp, "assistant.yml")
            val routerConfig = RouterConfig.from(config)
            val history = RouteHistoryStore(routerConfig.maxRouteHistory)
            val services =
                DispatchServices(
                    proxyServer = mockk(relaxed = true),
                    routerConfig = routerConfig,
                    assistantConfig = config,
                    routeHistory = history,
                )
            val registry = IntentHandlerRegistry(listOf(SkipIntentHandler()))
            val context =
                PipelineContext(
                    message =
                        InboundMessage(
                            player = "metal",
                            rawText = "ок",
                            displayText = "ок",
                            timestampMs = 1L,
                            server = null,
                            source = InboundMessage.Source.GAME,
                        ),
                    meta =
                        InboundMeta(
                            directedAtBot = false,
                            replyToBot = false,
                            continuationWithBot = false,
                            secondsSinceBot = null,
                            replyToPlayer = null,
                        ),
                    decision =
                        RouteDecision(
                            intent = RouteIntent.SKIP,
                            confidence = 0.9,
                            reason = "short",
                            raw = "{}",
                        ),
                )
            registry.dispatch(context, services)
            history.snapshot("metal").size shouldBe 1
            history.snapshot("metal").first().intent shouldBe RouteIntent.SKIP
        }
    }
})
