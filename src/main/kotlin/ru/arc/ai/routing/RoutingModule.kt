package ru.arc.ai.routing

import com.velocitypowered.api.proxy.ProxyServer
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.routing.context.RouterContextBuilder
import ru.arc.ai.routing.dispatch.DispatchServices
import ru.arc.ai.routing.dispatch.IntentHandlers
import ru.arc.ai.routing.dispatch.RouteDedup
import ru.arc.ai.routing.history.RouteHistoryStore
import ru.arc.ai.routing.observe.BotReplyTracker
import ru.arc.ai.routing.observe.ChatLineFormatter
import ru.arc.ai.routing.observe.ChatLog
import ru.arc.ai.routing.pipeline.MessagePipeline
import ru.arc.ai.routing.pipeline.ObserveStage
import ru.arc.ai.routing.pipeline.RouteStage
import ru.arc.ai.routing.router.AssistantRouter
import ru.arc.ai.routing.router.OpenRouterRouterLlmGateway
import ru.arc.ai.routing.router.RouterConfig
import ru.arc.ai.routing.router.RouterPrompt
import ru.arc.config.Config

object RoutingModule {
    var pipeline: MessagePipeline? = null
        private set

    val botReplyTracker = BotReplyTracker()

    var continuationWindowSec: Int = 90
        private set

    private var chatLineFormatter: ChatLineFormatter? = null
    private var surveyTimeoutMs: Long = 10 * 60 * 1000L

    fun sweepSurveyTimeouts() {
        ru.arc.ai.routing.survey.BugSurveySessionStore.closeIdle(surveyTimeoutMs)
    }

    fun init(
        proxyServer: ProxyServer,
        assistantConfig: Config,
        llmClient: OpenRouterLlmClient,
    ) {
        shutdown()
        val routerConfig = RouterConfig.from(assistantConfig)
        continuationWindowSec = routerConfig.continuationWindowSec
        surveyTimeoutMs =
            assistantConfig.integer("bug.survey.timeout-minutes", 10).toLong() * 60 * 1000L
        if (!routerConfig.enabled) return

        val observeMaxLines =
            maxOf(
                routerConfig.maxContextLines,
                assistantConfig.integer("chat.observe-max-lines", 40),
            )
        val chatLog = ChatLog(observeMaxLines)
        val routeHistory = RouteHistoryStore(routerConfig.maxRouteHistory)
        val formatter = ChatLineFormatter(routerConfig.observeFormat)
        chatLineFormatter = formatter

        val dispatchServices =
            DispatchServices(
                proxyServer = proxyServer,
                routerConfig = routerConfig,
                assistantConfig = assistantConfig,
                routeHistory = routeHistory,
            )
        val registry = IntentHandlers.create(dispatchServices)

        val routerPrompt = RouterPrompt.load(assistantConfig)
        val gateway = OpenRouterRouterLlmGateway(llmClient, routerConfig)
        val router = AssistantRouter(routerPrompt, gateway, routerConfig)
        val contextBuilder = RouterContextBuilder(chatLog, routeHistory, routerConfig)

        pipeline =
            MessagePipeline(
                observeStage =
                    ObserveStage(
                        chatLog = chatLog,
                        formatter = formatter,
                        botReplyTracker = botReplyTracker,
                        assistantConfig = assistantConfig,
                    ),
                routeStage = RouteStage(contextBuilder, router, routerConfig),
                registry = registry,
                dispatchServices = dispatchServices,
            )
    }

    fun formatBotObserveLine(message: String, botName: String): String {
        val formatter = chatLineFormatter
        return if (formatter != null) {
            formatter.formatBotLine(message, botName)
        } else {
            "[$botName] $message"
        }
    }

    fun recordBotReply(toPlayer: String?) {
        botReplyTracker.record(
            toPlayer = toPlayer,
            threadGapMs = continuationWindowSec * 1000L,
        )
    }

    fun shutdown() {
        pipeline = null
        chatLineFormatter = null
        botReplyTracker.reset()
        RouteDedup.clear()
        ru.arc.ai.routing.survey.BugSurveySessionStore.clear()
    }
}
