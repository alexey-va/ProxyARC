package ru.arc.ai.routing.dispatch.handlers

import org.slf4j.LoggerFactory
import ru.arc.ai.AssistantRunMode
import ru.arc.ai.routing.dispatch.DispatchServices
import ru.arc.ai.routing.dispatch.IntentHandler
import ru.arc.ai.routing.dispatch.RouteDedup
import ru.arc.ai.routing.dispatch.assistant.AssistantAgentDispatch
import ru.arc.ai.routing.pipeline.PipelineContext
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.tickets.IssueTicketStore

class BugIntentHandler(
    private val agent: AssistantAgentDispatch,
) : IntentHandler {
    override val intent = RouteIntent.BUG

    private val log = LoggerFactory.getLogger(BugIntentHandler::class.java)

    override fun dispatch(context: PipelineContext, services: DispatchServices) {
        if (!services.assistantConfig.bool("bug.enabled", true)) return
        if (agent.assistant() == null) return

        val player = context.message.player
        val message = context.message.displayText
        val open = IssueTicketStore.findOpenByReporter(player)

        val dedupKey =
            if (open != null) {
                "bug:${player.lowercase()}:${open.ticketId}:${message.lowercase().hashCode()}"
            } else {
                "bug:${player.lowercase()}:${message.lowercase().hashCode()}"
            }
        if (RouteDedup.isDuplicate(dedupKey)) return

        log.info(
            "Route bug {} «{}» ticket={}",
            player,
            message,
            open?.ticketId ?: "new",
        )

        agent.enqueue(context, AssistantRunMode.BUG)
    }
}
