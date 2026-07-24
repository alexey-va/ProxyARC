package ru.arc.ai.routing.pipeline

import com.velocitypowered.api.proxy.ProxyServer
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.arc.ai.routing.context.RouterContextBuilder
import ru.arc.ai.routing.dispatch.DispatchServices
import ru.arc.ai.routing.dispatch.IntentHandler
import ru.arc.ai.routing.dispatch.IntentHandlerRegistry
import ru.arc.ai.routing.history.RouteHistoryStore
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.observe.BotReplyTracker
import ru.arc.ai.routing.observe.ChatLineFormatter
import ru.arc.ai.routing.observe.ChatLog
import ru.arc.ai.routing.router.AssistantRouter
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouterConfig
import ru.arc.ai.routing.router.RouterLlmGateway
import ru.arc.ai.routing.router.RouterPrompt
import ru.arc.config.Config
import java.util.concurrent.CompletableFuture

class MessagePipelinePreviewTest : FreeSpec({
    "preview uses only deterministic routing and does not observe or dispatch" {
        val config =
            RouterConfig(
                enabled = true,
                model = "test",
                fallbackModel = "test",
                temperature = 0.0,
                maxTokens = 256,
                maxContextLines = 8,
                maxRouteHistory = 3,
                continuationWindowSec = 90,
                observeFormat = "[%time%] %player% » %message%",
                timeoutSec = 15,
                logSkipAtDebug = true,
                logRouteInfo = false,
                enabledIntents = setOf(RouteIntent.CHAT, RouteIntent.BUG),
                recentOpenTickets = 1,
                prefilterEnabled = true,
            )
        val chatLog = ChatLog(20)
        val routeHistory = RouteHistoryStore(5)
        var llmCalls = 0
        val gateway =
            RouterLlmGateway { _, _, _, _ ->
                llmCalls++
                CompletableFuture.completedFuture("""{"intent":"skip","confidence":1,"reason":"unexpected"}""")
            }
        val routeStage =
            RouteStage(
                contextBuilder = RouterContextBuilder(chatLog, routeHistory, config),
                router = AssistantRouter(RouterPrompt.forTest("test"), gateway, config),
                config = config,
            )
        val assistantConfig = mockk<Config>(relaxed = true)
        val observeStage =
            ObserveStage(
                chatLog = chatLog,
                formatter = ChatLineFormatter("[%time%] %player% » %message%"),
                botReplyTracker = BotReplyTracker(),
                assistantConfig = assistantConfig,
            )
        var dispatches = 0
        val handler =
            object : IntentHandler {
                override val intent = RouteIntent.CHAT

                override fun dispatch(
                    context: PipelineContext,
                    services: DispatchServices,
                ) {
                    dispatches++
                }
            }
        val registry = IntentHandlerRegistry(listOf(handler))
        val services =
            DispatchServices(
                proxyServer = mockk<ProxyServer>(relaxed = true),
                routerConfig = config,
                assistantConfig = assistantConfig,
                routeHistory = routeHistory,
            )
        val pipeline = MessagePipeline(observeStage, routeStage, registry, services)
        val context =
            PipelineContext(
                message =
                    InboundMessage(
                        player = "Tester",
                        rawText = "!скорен ты тут",
                        displayText = "скорен ты тут",
                        timestampMs = 1L,
                        server = "survival",
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
            )

        val decision = pipeline.preview(context)

        decision?.intent shouldBe RouteIntent.CHAT
        decision?.reason shouldBe "prefilter:directed_chat"
        llmCalls shouldBe 0
        dispatches shouldBe 0
        chatLog.snapshot() shouldBe emptyList()
        routeHistory.snapshot("Tester") shouldBe emptyList()
    }
})
