package ru.arc.ai.tickets

/**
 * Formats player ↔ Скорен dialog for Discord ticket embeds and updates.
 */
object BugTicketDialogContext {
    fun enrichAppend(
        agentText: String?,
        dialog: String,
        triggerMessage: String?,
        reporter: String,
    ): String =
        IssueTicketFormat.formatUpdateNote(
            agentNote = agentText,
            triggerMessage = triggerMessage,
            dialogForReporter = dialog,
        ).ifBlank { agentText?.trim().orEmpty() }
}
