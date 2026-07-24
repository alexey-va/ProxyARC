package ru.arc.ai.routing.survey

import ru.arc.ai.tickets.IssueTicket

/**
 * Bug-survey session is for continuing an existing ticket thread, not every new report.
 */
object BugSurveyStartPolicy {
    private val TICKET_ID = Regex("""RB-\d{5}""", RegexOption.IGNORE_CASE)

    fun shouldStartSurvey(
        surveyEnabled: Boolean,
        player: String,
        message: String,
        openTicket: IssueTicket?,
        investigation: BugSurveySession? = null,
    ): Boolean {
        if (!surveyEnabled) return false
        if (investigation != null) return true
        if (BugSurveySessionStore.isActive(player)) return true
        if (openTicket != null) return true
        if (TICKET_ID.containsMatchIn(message)) return true
        return false
    }
}
