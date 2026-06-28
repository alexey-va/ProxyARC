package ru.arc.ai.routing.context

import ru.arc.ai.routing.history.RouteRecord
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.tickets.IssueTicket
import ru.arc.ai.tickets.IssueTicketPromptFormat

data class RouterContext(
    val message: InboundMessage,
    val meta: InboundMeta,
    val recentChat: List<String>,
    val openTicket: IssueTicket?,
    val recentOpenTickets: List<IssueTicket>,
    val recentRoutes: List<RouteRecord>,
) {
    fun toUserContent(): String =
        buildString {
            appendLine("player=${message.player}")
            message.server?.let { appendLine("server=$it") }
            appendLine("message=${message.displayText}")
            appendLine("directed_at_bot=${meta.directedAtBot}")
            appendLine("reply_to_bot=${meta.replyToBot}")
            appendLine("continuation_with_bot=${meta.continuationWithBot}")
            appendLine("seconds_since_bot=${meta.secondsSinceBot ?: "null"}")
            appendLine("reply_to_player=${meta.replyToPlayer ?: "null"}")
            appendLine("source=${message.source.name.lowercase()}")
            appendLine("chat_allowed=${message.allowsChatRouting()}")
            appendLine()
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
