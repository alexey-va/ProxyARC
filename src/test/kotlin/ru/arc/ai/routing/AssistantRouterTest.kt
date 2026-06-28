package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import ru.arc.ai.routing.context.RouterContext
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.router.AssistantRouter
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouterConfig
import ru.arc.ai.routing.router.RouterLlmGateway
import ru.arc.ai.routing.router.RouterPrompt
import java.util.concurrent.CompletableFuture

class AssistantRouterTest : FreeSpec({
    "AssistantRouter" - {
        "retries on parse failure" {
            val routerConfig =
                RouterConfig(
                    enabled = true,
                    model = "openai/gpt-oss-20b:free",
                    fallbackModel = "deepseek/deepseek-v4-flash",
                    temperature = 0.0,
                    maxTokens = 120,
                    maxContextLines = 15,
                    maxRouteHistory = 5,
                    continuationWindowSec = 90,
                    observeFormat = "[%time%] %player% » %message%",
                    timeoutSec = 15,
                    logSkipAtDebug = true,
                    enabledIntents = setOf(RouteIntent.CHAT, RouteIntent.BUG),
                    recentOpenTickets = 3,
                )
            val gateway = mockk<RouterLlmGateway>()
            every {
                gateway.complete(any(), any(), any())
            } returnsMany
                listOf(
                    CompletableFuture.completedFuture(""),
                    CompletableFuture.completedFuture(
                        """{"intent":"chat","confidence":0.9,"reason":"ok"}""",
                    ),
                )
            val router =
                AssistantRouter(
                    prompt = RouterPrompt.forTest("test"),
                    gateway = gateway,
                    config = routerConfig,
                )
            val context =
                RouterContext(
                    message =
                        InboundMessage(
                            player = "grocer",
                            rawText = "скорен",
                            displayText = "скорен",
                            timestampMs = 1L,
                            server = null,
                            source = InboundMessage.Source.GAME,
                        ),
                    meta =
                        InboundMeta(
                            directedAtBot = true,
                            replyToBot = false,
                            continuationWithBot = false,
                            secondsSinceBot = null,
                            replyToPlayer = null,
                        ),
                    recentChat = emptyList(),
                    openTicket = null,
                    recentOpenTickets = emptyList(),
                    recentRoutes = emptyList(),
                )
            val decision = router.classify(context).join()
            decision.intent shouldBe RouteIntent.CHAT
            decision.parseOk shouldBe true
        }

        "maps disabled intent to skip" {
            val routerConfig =
                RouterConfig(
                    enabled = true,
                    model = "openai/gpt-oss-20b:free",
                    fallbackModel = "deepseek/deepseek-v4-flash",
                    temperature = 0.0,
                    maxTokens = 120,
                    maxContextLines = 15,
                    maxRouteHistory = 5,
                    continuationWindowSec = 90,
                    observeFormat = "[%time%] %player% » %message%",
                    timeoutSec = 15,
                    logSkipAtDebug = true,
                    enabledIntents = setOf(RouteIntent.CHAT),
                    recentOpenTickets = 3,
                )
            val gateway = mockk<RouterLlmGateway>()
            every {
                gateway.complete(any(), any(), any())
            } returns
                CompletableFuture.completedFuture(
                    """{"intent":"bug","confidence":0.9,"reason":"rtp"}""",
                )
            val router =
                AssistantRouter(
                    prompt = RouterPrompt.forTest("test"),
                    gateway = gateway,
                    config = routerConfig,
                )
            val context =
                RouterContext(
                    message =
                        InboundMessage(
                            player = "grocer",
                            rawText = "rtp",
                            displayText = "rtp не работает",
                            timestampMs = 1L,
                            server = "survival",
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
                    recentChat = emptyList(),
                    openTicket = null,
                    recentOpenTickets = emptyList(),
                    recentRoutes = emptyList(),
                )
            val decision = router.classify(context).join()
            decision.intent shouldBe RouteIntent.SKIP
            decision.reason shouldBe "intent_disabled:bug; rtp"
        }
    }
})
