package ru.arc.ai.tickets

import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel

object IssueTicketEmbedParser {
    private val ticketIdPattern = Regex("RB-\\d{5}", RegexOption.IGNORE_CASE)

    fun fromThread(
        thread: ThreadChannel,
        embed: MessageEmbed,
    ): IssueTicket? {
        val ticketId = parseTicketId(embed, thread.name) ?: return null
        val reporter =
            embed.fields
                .firstOrNull { it.name.equals("Репортёр", ignoreCase = true) }
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "unknown"
        val server =
            embed.fields
                .firstOrNull { it.name.equals("Сервер", ignoreCase = true) }
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val title = embed.title?.trim()?.takeIf { it.isNotEmpty() } ?: thread.name
        val summary =
            embed.description
                ?.trim()
                ?.replace("\n", " ")
                ?.take(300)
                ?.takeIf { it.isNotEmpty() }
        val status =
            if (thread.isArchived) {
                IssueTicket.STATUS_CLOSED
            } else {
                IssueTicket.STATUS_OPEN
            }
        val createdAt = thread.timeCreated.toInstant().toEpochMilli()
        return IssueTicket(
            ticketId = ticketId.uppercase(),
            threadId = thread.id,
            starterMessageId = null,
            reporter = reporter,
            title = title,
            createdAt = createdAt,
            status = status,
            summary = summary,
            server = server,
        )
    }

    private fun parseTicketId(embed: MessageEmbed, threadName: String): String? {
        val fromField =
            embed.fields
                .firstOrNull { it.name.equals("ID", ignoreCase = true) }
                ?.value
                ?.trim()
                ?.let { extractTicketId(it) }
        if (fromField != null) return fromField
        return extractTicketId(embed.title) ?: extractTicketId(threadName)
    }

    private fun extractTicketId(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return ticketIdPattern.find(text)?.value?.uppercase()
    }
}
