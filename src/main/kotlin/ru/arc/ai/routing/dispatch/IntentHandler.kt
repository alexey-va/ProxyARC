package ru.arc.ai.routing.dispatch

import ru.arc.ai.routing.pipeline.PipelineContext
import ru.arc.ai.routing.router.RouteIntent

/**
 * One routed scenario (pipeline). To add a new flow:
 * 1. Add [RouteIntent] + router prompt line
 * 2. Implement [IntentHandler] (or extend [handlers.AssistantIntentHandler] for agent scenarios)
 * 3. Register in [IntentHandlers.create]
 */
interface IntentHandler {
    val intent: RouteIntent

    fun dispatch(context: PipelineContext, services: DispatchServices)
}
