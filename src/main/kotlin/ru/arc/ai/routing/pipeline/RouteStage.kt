package ru.arc.ai.routing.pipeline

import org.slf4j.LoggerFactory
import ru.arc.ai.routing.dispatch.RouteDecisionPolicy
import ru.arc.ai.routing.context.RouterContextBuilder
import ru.arc.ai.routing.router.AssistantRouter
import ru.arc.ai.routing.router.RouteDecision
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouteLog
import ru.arc.ai.routing.router.RoutePrefilter
import ru.arc.ai.routing.router.RouterConfig
import java.util.concurrent.CompletableFuture

class RouteStage(
    private val contextBuilder: RouterContextBuilder,
    private val router: AssistantRouter,
    private val config: RouterConfig,
) {
    private val log = LoggerFactory.getLogger(RouteStage::class.java)

    /** Deterministic, no-LLM route preview for safe ops checks. */
    fun preview(context: PipelineContext): RouteDecision? {
        val routerContext = contextBuilder.build(context.message, context.meta)
        val decision = RoutePrefilter.classify(routerContext, config) ?: return null
        return RouteDecisionPolicy.apply(context.message, context.meta, decision, config)
    }

    fun processAsync(context: PipelineContext): CompletableFuture<PipelineContext> {
        val routerContext = contextBuilder.build(context.message, context.meta)
        val prefiltered = RoutePrefilter.classify(routerContext, config)
        val classified =
            if (prefiltered != null) {
                if (config.logRouteInfo) {
                    RouteLog.logClassified(
                        log,
                        context.message,
                        prefiltered,
                        prefiltered.model,
                    )
                }
                CompletableFuture.completedFuture(prefiltered)
            } else {
                router.classify(routerContext)
            }
        return classified.thenApply { decision ->
            val final = RouteDecisionPolicy.apply(context.message, context.meta, decision, config)
            if (config.logRouteInfo) {
                RouteLog.logPolicyAdjust(log, context.message, decision, final)
            }
            context.copy(decision = final)
        }.exceptionally { error ->
            RouteLog.logLlmError(log, context.message.player, "route-stage", error)
            context.copy(
                decision =
                    RouteDecision(
                        intent = RouteIntent.SKIP,
                        confidence = 0.0,
                        reason = "route_stage_error: ${RouteLog.describeError(error)}",
                        raw = "",
                        parseOk = false,
                    ),
            )
        }
    }
}
