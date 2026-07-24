package ru.arc.ai.routing.router

import ru.arc.ai.routing.context.RouterContext
import ru.arc.ai.routing.survey.SurveyResponseHeuristic

/**
 * Cheap deterministic routing for high-confidence cases.
 *
 * The LLM router is reserved for ambiguous continuations. Ordinary chat noise,
 * explicit Скорен mentions, and clear bug reports do not need a paid model call.
 */
object RoutePrefilter {
    fun classify(
        context: RouterContext,
        config: RouterConfig,
    ): RouteDecision? {
        if (!config.prefilterEnabled) return null

        val message = context.message
        val meta = context.meta
        val text = message.displayText
        val chatAllowed = message.allowsChatRouting(meta)

        if (RouterBugHeuristic.looksLikeOptOut(text)) {
            return decision(RouteIntent.SKIP, "prefilter:opt_out")
        }
        if (meta.directedAtBot && RouterBugHeuristic.looksLikeLowValueBotAddress(text)) {
            return decision(RouteIntent.SKIP, "prefilter:directed_ack")
        }

        val survey = context.activeBugSurvey
        if (survey != null) {
            if (!survey.isPrimary(message.player)) {
                return if (
                    RouterBugHeuristic.looksLikeBugReport(text) ||
                    RouterBugHeuristic.looksLikeSurveyDetail(text) ||
                    SurveyResponseHeuristic.isShortConfirmation(text)
                ) {
                    decision(RouteIntent.BUG, "prefilter:survey_witness")
                } else {
                    decision(RouteIntent.SKIP, "prefilter:survey_unrelated_witness")
                }
            }

            if (
                RouterBugHeuristic.looksLikeJoke(text) &&
                !RouterBugHeuristic.looksLikeUiBug(text)
            ) {
                return if (meta.directedAtBot && chatAllowed) {
                    decision(RouteIntent.CHAT, "prefilter:survey_direct_chat")
                } else {
                    decision(RouteIntent.SKIP, "prefilter:survey_joke")
                }
            }

            if (
                meta.directedAtBot &&
                chatAllowed &&
                RouterBugHeuristic.looksLikeOfftopicSmalltalk(text)
            ) {
                return decision(RouteIntent.CHAT, "prefilter:survey_direct_chat")
            }

            if (
                RouterBugHeuristic.looksLikeBugReport(text) ||
                RouterBugHeuristic.looksLikeUiBug(text) ||
                RouterBugHeuristic.looksLikeResolved(text) ||
                RouterBugHeuristic.looksLikeCloseTicket(text) ||
                RouterBugHeuristic.looksLikeSurveyDetail(text)
            ) {
                return decision(RouteIntent.BUG, "prefilter:survey_detail")
            }

            if (meta.replyToBot || meta.continuationWithBot) {
                if (RouterBugHeuristic.looksLikeLowValueContinuation(text)) {
                    return decision(RouteIntent.SKIP, "prefilter:survey_ack")
                }
                return decision(RouteIntent.BUG, "prefilter:survey_continuation")
            }

            if (meta.directedAtBot && chatAllowed) {
                return decision(RouteIntent.CHAT, "prefilter:survey_direct_chat")
            }
            return decision(RouteIntent.SKIP, "prefilter:survey_unrelated")
        }

        if (
            context.openTicket != null &&
            (
                RouterBugHeuristic.looksLikeResolved(text) ||
                    RouterBugHeuristic.looksLikeCloseTicket(text)
            )
        ) {
            return decision(RouteIntent.BUG, "prefilter:open_ticket_resolution")
        }

        if (RouterBugHeuristic.looksLikeBugReport(text)) {
            return decision(RouteIntent.BUG, "prefilter:clear_bug")
        }

        if (meta.directedAtBot) {
            return decision(RouteIntent.CHAT, "prefilter:directed_chat")
        }

        if (meta.replyToBot || meta.continuationWithBot) {
            if (
                !meta.directedAtBot &&
                meta.botRepliesInThread >= config.maxConsecutiveReplies
            ) {
                return decision(RouteIntent.SKIP, "prefilter:conversation_cap")
            }
            if (RouterBugHeuristic.looksLikeLowValueContinuation(text)) {
                return decision(RouteIntent.SKIP, "prefilter:low_value_continuation")
            }
            return decision(RouteIntent.CHAT, "prefilter:continuation_chat")
        }

        return decision(RouteIntent.SKIP, "prefilter:undirected_non_bug")
    }

    private fun decision(
        intent: RouteIntent,
        reason: String,
    ): RouteDecision =
        RouteDecision(
            intent = intent,
            confidence = if (intent == RouteIntent.SKIP) 0.99 else 0.98,
            reason = reason,
            raw = "",
            model = "prefilter",
            parseOk = true,
        )
}
