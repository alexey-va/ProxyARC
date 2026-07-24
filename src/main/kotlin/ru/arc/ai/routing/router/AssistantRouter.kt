package ru.arc.ai.routing.router

import org.slf4j.LoggerFactory
import ru.arc.ai.routing.context.RouterContext
import java.util.concurrent.CompletableFuture

class AssistantRouter(
    private val prompt: RouterPrompt,
    private val gateway: RouterLlmGateway,
    private val config: RouterConfig,
) {
    private val log = LoggerFactory.getLogger(AssistantRouter::class.java)

    fun classify(context: RouterContext): CompletableFuture<RouteDecision> {
        val userContent = context.toUserContent()
        val player = context.message.player
        return classifyWithModel(context, userContent, config.model).thenCompose { first ->
            if (first.parseOk) {
                CompletableFuture.completedFuture(applyIntentPolicy(first))
            } else if (first.isTransportFailure()) {
                // An ambiguous continuation is the only normal path reaching the
                // model after prefiltering. Do not immediately dispatch another
                // request to the same unavailable provider.
                CompletableFuture.completedFuture(
                    RouteDecision(
                        intent = RouteIntent.SKIP,
                        confidence = 0.0,
                        reason = "router_unavailable; ${first.reason}",
                        raw = first.raw,
                        model = first.model,
                        parseOk = true,
                    ),
                )
            } else {
                log.warn(
                    "Router parse failed player={} model={} reason={} — retry {}",
                    player,
                    first.model ?: config.model,
                    first.reason,
                    config.fallbackModel,
                )
                classifyWithModel(context, userContent, config.fallbackModel)
                    .thenApply { second ->
                        if (second.parseOk) {
                            applyIntentPolicy(second)
                        } else {
                            val heuristic =
                                RouterHeuristicFallback.apply(context, second)
                            if (heuristic.parseOk && config.logRouteInfo) {
                                RouteLog.logClassified(
                                    log,
                                    context.message,
                                    heuristic,
                                    heuristic.model,
                                )
                            }
                            applyIntentPolicy(heuristic)
                        }
                    }
            }
        }
    }

    private fun RouteDecision.isTransportFailure(): Boolean =
        reason.startsWith("llm_error:")

    private fun applyIntentPolicy(decision: RouteDecision): RouteDecision {
        if (config.isIntentEnabled(decision.intent)) return decision
        return decision.copy(
            intent = RouteIntent.SKIP,
            reason = "intent_disabled:${decision.intent.wireName()}; ${decision.reason}",
        )
    }

    private fun classifyWithModel(
        context: RouterContext,
        userContent: String,
        model: String,
    ): CompletableFuture<RouteDecision> =
        gateway
            .complete(prompt.systemPrompt, userContent, model, context.message.player)
            .handle { raw, error ->
                if (error != null) {
                    RouteLog.logLlmError(log, context.message.player, model, error)
                    RouteDecision(
                        intent = RouteIntent.SKIP,
                        confidence = 0.0,
                        reason = "llm_error: ${RouteLog.describeError(error)}",
                        raw = "",
                        model = model,
                        parseOk = false,
                    )
                } else {
                    val parsed = RouterJsonParser.parse(raw).copy(model = model)
                    if (config.logRouteInfo) {
                        RouteLog.logClassified(log, context.message, parsed, model)
                    }
                    parsed
                }
            }
}
