package ru.arc.ai.routing.dispatch.handlers

import org.slf4j.LoggerFactory
import ru.arc.ai.AssistantRunMode
import ru.arc.ai.routing.dispatch.DispatchServices
import ru.arc.ai.routing.dispatch.IntentHandler
import ru.arc.ai.routing.dispatch.RouteDedup
import ru.arc.ai.routing.dispatch.assistant.AssistantAgentDispatch
import ru.arc.ai.routing.dispatch.assistant.BugSurveyAgentDispatch
import ru.arc.ai.routing.pipeline.PipelineContext
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.survey.BugSurveySessionStore
import ru.arc.ai.routing.survey.BugSurveyStartPolicy
import ru.arc.ai.tickets.IssueTicketStore

class BugIntentHandler(
    private val surveyDispatch: BugSurveyAgentDispatch,
    private val legacyAgent: AssistantAgentDispatch,
) : IntentHandler {
    override val intent = RouteIntent.BUG

    private val log = LoggerFactory.getLogger(BugIntentHandler::class.java)

    override fun dispatch(
        context: PipelineContext,
        services: DispatchServices,
    ) {
        if (services.assistantConfig.bool("bug.observe-only", false)) return
        if (!services.assistantConfig.bool("bug.enabled", true)) return

        val player = context.message.player
        val message = context.message.displayText
        val surveyEnabled = services.assistantConfig.bool("bug.survey.enabled", true)
        val globalWindowSec =
            services.assistantConfig.integer("bug.survey.global-inquiry-window-sec", 300).coerceIn(30, 900)
        val globalWindowMs = globalWindowSec * 1000L

        val investigation =
            BugSurveySessionStore.resolveSession(
                player = player,
                message = message,
                meta = context.meta,
                globalInquiryWindowMs = globalWindowMs,
            )
        val open =
            IssueTicketStore.findOpenByReporter(player)
                ?: investigation?.ticketId?.let { IssueTicketStore.find(it)?.takeIf { t -> t.status == "open" } }

        val dedupKey =
            if (open != null) {
                "bug:${player.lowercase()}:${open.ticketId}:${message.lowercase().hashCode()}"
            } else {
                "bug:${player.lowercase()}:${message.lowercase().hashCode()}"
            }
        if (RouteDedup.isDuplicate(dedupKey)) {
            log.debug("Route bug dedup skip {} «{}»", player, message)
            return
        }

        val primary = investigation?.player ?: player
        val witness = investigation != null && !investigation.isPrimary(player)

        log.info(
            "Route bug {} «{}» ticket={} survey={} primary={} witness={}",
            player,
            message,
            open?.ticketId ?: investigation?.ticketId ?: "new",
            surveyEnabled,
            primary,
            witness,
        )

        val useSurvey =
            BugSurveyStartPolicy.shouldStartSurvey(
                surveyEnabled = surveyEnabled,
                player = player,
                message = message,
                openTicket = open,
                investigation = investigation,
            )

        if (useSurvey) {
            val session = BugSurveySessionStore.openOrTouch(primary)
            if (!player.equals(primary, ignoreCase = true)) {
                BugSurveySessionStore.addParticipant(primary, player)
            }
            surveyDispatch.enqueue(context, session)
        } else {
            legacyAgent.enqueue(context, AssistantRunMode.BUG)
        }
    }
}
