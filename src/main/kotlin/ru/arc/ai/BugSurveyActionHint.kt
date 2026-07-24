package ru.arc.ai

import ru.arc.ai.routing.router.RouterBugHeuristic
import ru.arc.ai.routing.survey.BugSurveySessionStore
import ru.arc.ai.tickets.IssueTicketTitles
import ru.arc.ai.tickets.PlayerWorldNames

/**
 * Per-turn hint so the model calls tools on first pass (avoids пропускаю → nudge → extra LLM).
 */
object BugSurveyActionHint {
    private val vaguePatterns =
        listOf(
            "есть бага",
            "есть баг",
            "баг",
            "не работает",
            "не пашет",
            "не паш",
            "сломал",
            "че то не так",
            "что-то не так",
        )

    fun turnHint(
        player: String?,
        message: String?,
        ticketId: String?,
    ): String? {
        val name = player?.trim().orEmpty()
        val text = message?.trim().orEmpty()
        if (name.isEmpty() || text.isEmpty()) return null

        val session = BugSurveySessionStore.findForPlayer(name)
        val ticket = ticketId?.trim()?.takeIf { it.isNotEmpty() } ?: session?.ticketId
        val inSurvey = session != null || ticket != null
        if (!inSurvey) {
            return legacyNewReportHint(text)
        }

        val lower = text.lowercase()
        val ticketRef = ticket ?: "RB-xxxxx"

        val action =
            when {
                ticket == null && RouterBugHeuristic.looksLikeSurveyDetail(text) ->
                    "createissueticket + sendprivatemessage (Russian); do not listissuetickets; no sendglobalmessage"
                RouterBugHeuristic.looksLikeResolved(text) ->
                    "updateissueticket ticketId=$ticketRef status=closed title=${IssueTicketTitles.CLOSED_PREFIX}… " +
                        "appendDescription=player resolved; then sendprivatemessage (Russian): ok closed ticket"
                RouterBugHeuristic.looksLikeUiBug(text) ->
                    "updateissueticket ticketId=$ticketRef appendDescription=menu/UI quote; " +
                        "then sendprivatemessage (Russian) that it was logged to $ticketRef"
                isVagueReport(lower) ->
                    "sendprivatemessage (Russian): one question — what broke, command, server"
                RouterBugHeuristic.looksLikeSurveyDetail(text) ||
                    (RouterBugHeuristic.looksLikeBugReport(text) && !RouterBugHeuristic.looksLikeJoke(text)) ->
                    "updateissueticket ticketId=$ticketRef appendDescription=new details; " +
                        "then sendprivatemessage (Russian) logged to $ticketRef; no sendglobalmessage"
                RouterBugHeuristic.looksLikeJoke(text) && !RouterBugHeuristic.looksLikeBugReport(text) ->
                    "completebugsurvey; sendprivatemessage (Russian) not a bug — no createissueticket"
                else ->
                    "sendprivatemessage (Russian); if new facts → updateissueticket $ticketRef"
            }

        return formatHint(action)
    }

    private fun legacyNewReportHint(text: String): String? {
        if (!RouterBugHeuristic.looksLikeBugReport(text)) return null
        val action =
            if (isVagueReport(text.lowercase())) {
                "sendprivatemessage (Russian): what broke, command, server; createissueticket only if enough detail"
            } else {
                "createissueticket + sendprivatemessage (Russian); skip listissuetickets on fresh report"
            }
        return formatHint(action)
    }

    private fun formatHint(action: String): String =
        buildString {
            appendLine("turn action (tools first, then single line SKIP):")
            appendLine(action)
            append("forbidden: SKIP without calling tools this turn")
        }

    private fun isVagueReport(lower: String): Boolean {
        if (lower.length > 48) return false
        if (RouterBugHeuristic.looksLikeSurveyDetail(lower)) return false
        if (PlayerWorldNames.inferFromText(lower) != null) return false
        return vaguePatterns.any { lower.contains(it) } && !lower.contains("/")
    }
}
