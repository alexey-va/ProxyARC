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
            } else {
                log.warn(
                    "Router parse failed player={} model={} reason={} — retry {}",
                    player,
                    first.model ?: config.model,
                    first.reason,
                    config.fallbackModel,
                )
                classifyWithModel(context, userContent, config.fallbackModel)
                    .thenApply(::applyIntentPolicy)
            }
        }
    }

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
            .complete(prompt.systemPrompt, userContent, model)
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
