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
        return classifyWithModel(userContent, config.model).thenCompose { first ->
            val resolved = applyIntentPolicy(first)
            if (resolved.parseOk) {
                CompletableFuture.completedFuture(resolved)
            } else {
                log.debug(
                    "Router parse failed on {}, retrying with {}",
                    config.model,
                    config.fallbackModel,
                )
                classifyWithModel(userContent, config.fallbackModel).thenApply(::applyIntentPolicy)
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
        userContent: String,
        model: String,
    ): CompletableFuture<RouteDecision> =
        gateway
            .complete(prompt.systemPrompt, userContent, model)
            .handle { raw, error ->
                if (error != null) {
                    log.warn("Router LLM error (model={}): {}", model, error.message)
                    RouteDecision(
                        intent = RouteIntent.SKIP,
                        confidence = 0.0,
                        reason = "llm_error: ${error.message}",
                        raw = "",
                        model = model,
                        parseOk = false,
                    )
                } else {
                    RouterJsonParser.parse(raw).copy(model = model)
                }
            }
}
