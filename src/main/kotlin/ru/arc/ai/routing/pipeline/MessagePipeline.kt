package ru.arc.ai.routing.pipeline

import org.slf4j.LoggerFactory
import ru.arc.ai.routing.dispatch.DispatchServices
import ru.arc.ai.routing.dispatch.IntentHandlerRegistry

class MessagePipeline(
    private val observeStage: ObserveStage,
    private val routeStage: RouteStage,
    private val registry: IntentHandlerRegistry,
    private val dispatchServices: DispatchServices,
) {
    private val log = LoggerFactory.getLogger(MessagePipeline::class.java)

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
}
