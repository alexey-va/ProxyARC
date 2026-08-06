package ru.arc.ai.routing.dispatch

import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.router.RouteDecision
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouterBugHeuristic
import ru.arc.ai.routing.router.RouterConfig
import ru.arc.ai.routing.survey.BugSurveySessionStore

object RouteDecisionPolicy {
    fun apply(
        message: InboundMessage,
        meta: InboundMeta,
        decision: RouteDecision,
        config: RouterConfig,
    ): RouteDecision {
        var result = decision
        if (!config.isIntentEnabled(result.intent)) {
            result =
                result.copy(
                    intent = RouteIntent.SKIP,
                    reason = "intent_disabled:${result.intent.wireName()}; ${result.reason}",
                )
        }
        if (result.intent == RouteIntent.CHAT && !message.allowsChatRouting(meta)) {
            result =
                result.copy(
                    intent = RouteIntent.SKIP,
                    reason = "chat_requires_!_prefix; ${result.reason}",
                )
        }
        result = applyActiveSurveyOverride(message, meta, result)
        result = applyOpenTicketOverride(message, result)
        result = applyBugLikeSkipOverride(message, result)
        result = applyBugObserveOnly(message, meta, result, config)
        return result
    }

    /**
     * Observe bug reports in chat history, but never dispatch the bug agent,
     * survey, ticket tools, or player-facing messages.
     */
    internal fun applyBugObserveOnly(
        message: InboundMessage,
        meta: InboundMeta,
        decision: RouteDecision,
        config: RouterConfig,
    ): RouteDecision {
        if (!config.bugObserveOnly) return decision

        val activeSurvey = BugSurveySessionStore.findForPlayer(message.player)
        val isBugConversation =
            decision.intent == RouteIntent.BUG ||
                RouterBugHeuristic.looksLikeBugReport(message.displayText) ||
                RouterBugHeuristic.mentionsBugTopic(message.displayText) ||
                vagueBugAttempt(message.displayText) ||
                (activeSurvey != null && shouldForceBugDuringSurvey(message, meta))
        if (!isBugConversation) return decision

        return decision.copy(
            intent = RouteIntent.SKIP,
            reason = "bug_observe_only; ${decision.reason}",
        )
    }

    /** Router LLM often skips «есть бага» — preserve the bug classification. */
    internal fun applyBugLikeSkipOverride(
        message: InboundMessage,
        decision: RouteDecision,
    ): RouteDecision {
        if (decision.intent != RouteIntent.SKIP) return decision
        val text = message.displayText
        if (RouterBugHeuristic.looksLikeJoke(text) && !RouterBugHeuristic.looksLikeUiBug(text)) {
            return decision
        }
        if (!RouterBugHeuristic.looksLikeBugReport(text) && !vagueBugAttempt(text)) {
            return decision
        }
        return decision.copy(
            intent = RouteIntent.BUG,
            reason = "bug_like_not_skip; ${decision.reason}",
        )
    }

    /** Close / resolved messages with an open RB ticket remain bug intent, not chat. */
    internal fun applyOpenTicketOverride(
        message: InboundMessage,
        decision: RouteDecision,
    ): RouteDecision {
        if (decision.intent == RouteIntent.BUG) return decision
        val open =
            ru.arc.ai.tickets.IssueTicketStore.findOpenByReporter(message.player)
                ?: return decision
        val text = message.displayText
        if (!RouterBugHeuristic.looksLikeResolved(text) && !RouterBugHeuristic.looksLikeCloseTicket(text)) {
            return decision
        }
        return decision.copy(
            intent = RouteIntent.BUG,
            reason = "open_ticket_resolved_or_close; ${decision.reason}",
        )
    }

    private fun shouldForceBugDuringSurvey(
        message: InboundMessage,
        meta: InboundMeta,
    ): Boolean {
        val text = message.displayText
        if (RouterBugHeuristic.looksLikeOfftopicSmalltalk(text)) return false
        if (meta.replyToBot || meta.continuationWithBot) return true
        if (RouterBugHeuristic.looksLikeBugReport(text)) return true
        if (RouterBugHeuristic.looksLikeUiBug(text)) return true
        if (RouterBugHeuristic.looksLikeResolved(text)) return true
        if (RouterBugHeuristic.looksLikeSurveyDetail(text)) return true
        return vagueBugAttempt(text)
    }

    private fun vagueBugAttempt(text: String): Boolean {
        return RouterBugHeuristic.looksLikeVagueBugClaim(text)
    }

    /**
     * Primary reporter in bug-survey must not be silently skipped — LLM often mislabels
     * follow-ups («в меню написано…») as skip/chat.
     */
    internal fun applyActiveSurveyOverride(
        message: InboundMessage,
        meta: InboundMeta,
        decision: RouteDecision,
    ): RouteDecision {
        val survey = BugSurveySessionStore.findForPlayer(message.player) ?: return decision
        if (!survey.isPrimary(message.player)) return decision
        if (decision.intent == RouteIntent.BUG) return decision
        if (!shouldForceBugDuringSurvey(message, meta)) return decision
        if (RouterBugHeuristic.looksLikeJoke(message.displayText) &&
            !RouterBugHeuristic.looksLikeUiBug(message.displayText)
        ) {
            return decision
        }

        val inThread = meta.replyToBot || meta.continuationWithBot
        return when (decision.intent) {
            RouteIntent.SKIP ->
                decision.copy(
                    intent = RouteIntent.BUG,
                    reason = "survey_primary_not_skip; ${decision.reason}",
                )
            RouteIntent.CHAT ->
                if (!message.allowsChatRouting(meta) || inThread) {
                    decision.copy(
                        intent = RouteIntent.BUG,
                        reason = "survey_primary_continuation; ${decision.reason}",
                    )
                } else {
                    decision
                }
            RouteIntent.BUG -> decision
        }
    }
}
