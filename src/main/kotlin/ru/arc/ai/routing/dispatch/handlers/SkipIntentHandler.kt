package ru.arc.ai.routing.dispatch.handlers

import org.slf4j.LoggerFactory
import ru.arc.ai.routing.dispatch.DispatchServices
import ru.arc.ai.routing.dispatch.IntentHandler
import ru.arc.ai.routing.pipeline.PipelineContext
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouteLog

class SkipIntentHandler : IntentHandler {
    override val intent = RouteIntent.SKIP

    private val log = LoggerFactory.getLogger(SkipIntentHandler::class.java)

    override fun dispatch(context: PipelineContext, services: DispatchServices) {
        val decision = context.decision ?: return
        if (services.routerConfig.logRouteInfo) {
            RouteLog.logDispatch(log, context, "skip")
        } else if (services.routerConfig.logSkipAtDebug) {
            log.debug(
                "Route skip {} «{}» reason={} conf={}",
                context.message.player,
                context.message.displayText,
                decision.reason,
                decision.confidence,
            )
        }
    }
}
