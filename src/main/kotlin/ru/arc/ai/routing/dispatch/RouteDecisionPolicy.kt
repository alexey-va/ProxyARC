package ru.arc.ai.routing.dispatch

import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.router.RouteDecision
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouterConfig

object RouteDecisionPolicy {
    fun apply(
        message: InboundMessage,
        decision: RouteDecision,
        config: RouterConfig,
    ): RouteDecision {
        var result = decision
        if (!config.isIntentEnabled(result.intent)) {
            result =
                result.copy(
                    intent = RouteIntent.SKIP,
                    reason = "intent_disabled:${result.intent.wireName()}; ${result.reason}",
                )
        }
        if (result.intent == RouteIntent.CHAT && !message.allowsChatRouting()) {
            result =
                result.copy(
                    intent = RouteIntent.SKIP,
                    reason = "chat_requires_!_prefix; ${result.reason}",
                )
        }
        return result
    }
}
