package ru.arc.ai.routing.pipeline

import org.slf4j.LoggerFactory
import ru.arc.ai.routing.dispatch.RouteDecisionPolicy
import ru.arc.ai.routing.context.RouterContextBuilder
import ru.arc.ai.routing.router.AssistantRouter
import ru.arc.ai.routing.router.RouteDecision
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouteLog
import ru.arc.ai.routing.router.RouterConfig
import java.util.concurrent.CompletableFuture

class RouteStage(
    private val contextBuilder: RouterContextBuilder,
    private val router: AssistantRouter,
    private val config: RouterConfig,
) {
    private val log = LoggerFactory.getLogger(RouteStage::class.java)

    fun processAsync(context: PipelineContext): CompletableFuture<PipelineContext> {
        val routerContext = contextBuilder.build(context.message, context.meta)
        return router.classify(routerContext).thenApply { decision ->
            val final = RouteDecisionPolicy.apply(context.message, decision, config)
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
