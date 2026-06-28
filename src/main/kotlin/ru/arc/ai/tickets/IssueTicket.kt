package ru.arc.ai.tickets

data class IssueTicket(
    val ticketId: String,
    val threadId: String,
    val starterMessageId: String?,
    val reporter: String,
    val title: String,
    val createdAt: Long,
    val status: String = STATUS_OPEN,
    val summary: String? = null,
    val server: String? = null,
) {
    companion object {
        const val STATUS_OPEN = "open"
        const val STATUS_CLOSED = "closed"
    }

    fun isOpen(): Boolean = status == STATUS_OPEN
}
