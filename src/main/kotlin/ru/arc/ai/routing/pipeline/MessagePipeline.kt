package ru.arc.ai.routing.pipeline

import org.slf4j.LoggerFactory
import ru.arc.ai.routing.dispatch.DispatchServices
import ru.arc.ai.routing.dispatch.IntentHandlerRegistry
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.velocity.Velocity
import java.util.concurrent.TimeUnit

class MessagePipeline(
    private val observeStage: ObserveStage,
    private val routeStage: RouteStage,
    private val registry: IntentHandlerRegistry,
    private val dispatchServices: DispatchServices,
) {
    private val log = LoggerFactory.getLogger(MessagePipeline::class.java)

    /** No observation, dispatch, tools, PM, ticket, or LLM call. */
    fun preview(context: PipelineContext) = routeStage.preview(context)

    fun run(context: PipelineContext) {
        try {
            val observed = observeStage.process(context)
            routeStage.processAsync(observed).thenAccept { routed ->
                try {
                    registry.dispatch(routed, dispatchServices)
                } catch (e: Exception) {
                    log.error("Dispatch failed: {}", e.message, e)
                }
            }
        } catch (e: Exception) {
            log.error("Message pipeline failed: {}", e.message, e)
        }
    }

    fun runAndAwait(
        context: PipelineContext,
        timeoutSec: Long,
    ): PipelineContext {
        val observed = observeStage.process(context)
        val routed = routeStage.processAsync(observed).get(timeoutSec, TimeUnit.SECONDS)
        registry.dispatch(routed, dispatchServices)
        return routed
    }

    fun awaitAgents(
        intent: RouteIntent,
        timeoutSec: Long,
    ): String {
        val assistant =
            when (intent) {
                RouteIntent.CHAT -> Velocity.chatAssistant
                RouteIntent.BUG -> Velocity.bugSurveyAssistant
                RouteIntent.SKIP -> null
            } ?: return "no_active_agent"
        val future = assistant.currentRequest ?: return "no_active_agent"
        if (future.isDone) return "completed:0"
        return try {
            future.get(timeoutSec, TimeUnit.SECONDS)
            "completed:1"
        } catch (e: Exception) {
            "timeout:${e.message?.take(40)}"
        }
    }
}
