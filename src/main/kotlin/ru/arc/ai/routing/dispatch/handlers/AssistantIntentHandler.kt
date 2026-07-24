package ru.arc.ai.routing.dispatch.handlers

import ru.arc.ai.AssistantRunMode
import ru.arc.ai.routing.dispatch.DispatchServices
import ru.arc.ai.routing.dispatch.IntentHandler
import ru.arc.ai.routing.dispatch.assistant.AssistantAgentDispatch
import ru.arc.ai.routing.pipeline.PipelineContext
import ru.arc.ai.routing.router.RouteIntent

/**
 * Routed scenario that runs the shared Скорен agent with a specific [AssistantRunMode].
 *
 * Subclass only when you need custom gates ([shouldDispatch]) or context lines ([extraHistoryLines]).
 */
abstract class AssistantIntentHandler(
    private val agent: AssistantAgentDispatch,
    override val intent: RouteIntent,
    private val mode: AssistantRunMode,
    private val deliverPublicReply: Boolean = false,
) : IntentHandler {
    override fun dispatch(context: PipelineContext, services: DispatchServices) {
        if (!shouldDispatch(context, services)) return
        agent.enqueue(
            context = context,
            mode = mode,
            extraHistoryLines = extraHistoryLines(context, services),
            deliverPublicReply = deliverPublicReply,
        )
    }

    open fun shouldDispatch(context: PipelineContext, services: DispatchServices): Boolean =
        agent.assistant() != null

    open fun extraHistoryLines(
        context: PipelineContext,
        services: DispatchServices,
    ): List<Pair<String, String>> = emptyList()
}
