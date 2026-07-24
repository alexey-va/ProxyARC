package ru.arc.ai.routing.context

import ru.arc.ai.routing.history.RouteRecord
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.tickets.IssueTicket
import ru.arc.ai.tickets.IssueTicketPromptFormat
import ru.arc.ai.tickets.PlayerWorldNames
import ru.arc.ai.routing.survey.BugSurveySession

data class RouterContext(
    val message: InboundMessage,
    val meta: InboundMeta,
    val recentChat: List<String>,
    val openTicket: IssueTicket?,
    val recentOpenTickets: List<IssueTicket>,
    val recentRoutes: List<RouteRecord>,
    val activeBugSurvey: BugSurveySession? = null,
) {
    fun toUserContent(): String {
        return buildString {
            appendLine("player=${message.player}")
            val world =
                PlayerWorldNames.resolveDisplay(
                    proxyOrHint = message.server,
                    messageText = message.displayText,
                )
            if (world != "неизвестно") {
                appendLine("world=$world")
            }
            appendLine("message=${message.displayText}")
            appendLine("directed_at_bot=${meta.directedAtBot}")
            appendLine("reply_to_bot=${meta.replyToBot}")
            appendLine("continuation_with_bot=${meta.continuationWithBot}")
            appendLine("seconds_since_bot=${meta.secondsSinceBot ?: "null"}")
            appendLine("reply_to_player=${meta.replyToPlayer ?: "null"}")
            appendLine("source=${message.source.name.lowercase()}")
            appendLine("chat_allowed=${message.allowsChatRouting(meta)}")
            appendLine()
            if (activeBugSurvey != null) {
                appendLine("active_bug_survey=true")
                appendLine("survey_primary=${activeBugSurvey.player}")
                activeBugSurvey.ticketId?.let { appendLine("survey_ticket=$it") }
                activeBugSurvey.topicHint?.let { appendLine("survey_topic=$it") }
                if (activeBugSurvey.participants.size > 1) {
                    appendLine("survey_participants=${activeBugSurvey.participants.joinToString()}")
                }
                if (activeBugSurvey.awaitingGlobalResponses) {
                    appendLine("awaiting_global_bug_responses=true")
                    activeBugSurvey.lastGlobalQuestion?.let { appendLine("global_question=$it") }
                }
                if (!activeBugSurvey.isPrimary(message.player)) {
                    appendLine("survey_witness=true")
                }
                appendLine()
            }
            if (recentChat.isNotEmpty()) {
                appendLine("recent_chat:")
                recentChat.forEach { appendLine(it) }
                appendLine()
            }
            if (openTicket != null) {
                appendLine("open_ticket:")
                appendLine("id=${openTicket.ticketId}")
                appendLine("status=${openTicket.status}")
                appendLine("summary=${openTicket.title}")
                openTicket.server?.let { appendLine("server=$it") }
                appendLine()
            }
            if (recentOpenTickets.isNotEmpty()) {
                appendLine("recent_open_tickets:")
                recentOpenTickets.forEach { appendLine(IssueTicketPromptFormat.formatListLine(it)) }
                appendLine()
            }
            if (recentRoutes.isNotEmpty()) {
                appendLine("recent_routes:")
                recentRoutes.forEach { appendLine("- ${it.formatLine()}") }
            }
        }.trimEnd()
    }
}
