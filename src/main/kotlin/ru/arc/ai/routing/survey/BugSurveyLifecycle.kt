package ru.arc.ai.routing.survey

import ru.arc.ai.AssistantRunMode
import ru.arc.ai.routing.router.RouterBugHeuristic

object BugSurveyLifecycle {
    fun shouldOpenAfterPrivateMessage(
        surveyCompleted: Boolean,
        isResolutionWithoutOpenTicket: Boolean = false,
    ): Boolean = !surveyCompleted && !isResolutionWithoutOpenTicket

    fun shouldResolveWithoutTools(
        mode: AssistantRunMode,
        hadToolCalls: Boolean,
        message: String?,
    ): Boolean {
        val text = message?.trim().orEmpty()
        return mode.blocksPublicReply() &&
            !hadToolCalls &&
            text.isNotEmpty() &&
            (
                RouterBugHeuristic.looksLikeResolved(text) ||
                    RouterBugHeuristic.looksLikeCloseTicket(text)
            )
    }
}
