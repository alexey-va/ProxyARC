package ru.arc.ai.tickets

import ru.arc.ai.routing.survey.BugSurveySessionStore

/** Which bug-agent tools to expose for the current turn. */
object BugToolPolicy {
    fun shouldOfferListIssueTickets(reporter: String?): Boolean {
        val name = reporter?.trim().orEmpty()
        if (name.isEmpty()) return false
        val survey = BugSurveySessionStore.findForPlayer(name)
        if (!survey?.ticketId.isNullOrBlank()) return false
        return IssueTicketStore.findOpenByReporter(name) != null
    }
}
