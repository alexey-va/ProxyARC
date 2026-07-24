package ru.arc.ai

import ru.arc.ai.routing.router.RouterBugHeuristic

/** Deterministic bug-intake behavior when the LLM provider is unavailable. */
object BugAgentRecoveryPolicy {
    enum class Action {
        SUPPRESS,
        ASK_DETAILS,
        CREATE_TICKET,
    }

    fun decide(
        mode: AssistantRunMode,
        player: String?,
        message: String?,
        activeSurveyWithoutTicket: Boolean,
    ): Action {
        val text = message?.trim().orEmpty()
        if (!mode.blocksPublicReply() || player.isNullOrBlank() || text.isEmpty()) {
            return Action.SUPPRESS
        }
        if (
            RouterBugHeuristic.looksLikeOptOut(text) ||
            RouterBugHeuristic.looksLikeTrollNoise(text) ||
            RouterBugHeuristic.looksLikeOfftopicSmalltalk(text) ||
            (
                RouterBugHeuristic.looksLikeJoke(text) &&
                    !RouterBugHeuristic.looksLikeBugReport(text)
            )
        ) {
            return Action.SUPPRESS
        }
        if (
            activeSurveyWithoutTicket &&
            text.length >= 8 &&
            RouterBugHeuristic.looksLikeSurveyDetail(text) &&
            RouterBugHeuristic.looksLikeBugReport(text)
        ) {
            return Action.CREATE_TICKET
        }
        return Action.ASK_DETAILS
    }
}
