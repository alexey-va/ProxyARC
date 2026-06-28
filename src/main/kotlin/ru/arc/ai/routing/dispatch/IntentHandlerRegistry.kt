package ru.arc.ai.routing.dispatch

import org.slf4j.LoggerFactory
import ru.arc.ai.routing.pipeline.PipelineContext
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouteLog

class IntentHandlerRegistry(
    private val handlers: List<IntentHandler>,
) {
    private val log = LoggerFactory.getLogger(IntentHandlerRegistry::class.java)
    private val byIntent = handlers.associateBy { it.intent }

    fun dispatch(context: PipelineContext, services: DispatchServices) {
        val decision = context.decision ?: return
        val player = context.message.player
        val message = context.message.displayText

        services.routeHistory.append(
            player = player,
            intent = decision.intent,
            messageSnippet = message,
            confidence = decision.confidence,
        )

        val handler = byIntent[decision.intent]
        if (handler == null) {
            log.warn(
                "No handler for intent {} player={} «{}» — skip",
                decision.intent.wireName(),
                player,
                message,
            )
            byIntent[RouteIntent.SKIP]?.dispatch(context, services)
            return
        }
        if (services.routerConfig.logRouteInfo) {
            RouteLog.logDispatch(log, context, handler.javaClass.simpleName)
        }
        handler.dispatch(context, services)
    }
}
