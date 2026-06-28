package ru.arc.ai.tickets

object IssueTicketPromptFormat {
    fun formatListLine(ticket: IssueTicket): String {
        val server = ticket.server?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
        val summary =
            ticket.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { summary ->
                " «${summary.take(120)}»"
            } ?: ""
        return "- ${ticket.ticketId} ${ticket.status} ${ticket.reporter}$server ${ticket.title}$summary"
    }

    fun formatOpenTicketBlock(ticket: IssueTicket): String =
        buildString {
            appendLine("id=${ticket.ticketId}")
            appendLine("status=${ticket.status}")
            appendLine("reporter=${ticket.reporter}")
            ticket.server?.let { appendLine("server=$it") }
            appendLine("title=${ticket.title}")
            ticket.summary?.let { appendLine("summary=${it.take(500)}") }
        }.trimEnd()
}
