package ru.arc.ai.routing.router

import ru.arc.ai.routing.context.RouterContext

/**
 * When router LLM returns empty/unparseable JSON, infer intent from pipeline meta
 * and lightweight text heuristics so messages are not silently dropped.
 */
object RouterHeuristicFallback {
    fun apply(
        context: RouterContext,
        failed: RouteDecision,
    ): RouteDecision {
        val meta = context.meta
        val message = context.message
        val text = message.displayText
        val bugLike = RouterBugHeuristic.looksLikeBugReport(text)

        if (context.activeBugSurvey != null && (meta.replyToBot || meta.continuationWithBot)) {
            if (RouterBugHeuristic.looksLikeJoke(text)) {
                return heuristic(
                    intent = RouteIntent.CHAT,
                    reason = "heuristic:survey_joke_or_chat; llm=${failed.reason}",
                    failed = failed,
                )
            }
            if (bugLike) {
                return heuristic(
                    intent = RouteIntent.BUG,
                    reason = "heuristic:survey_bug_detail; llm=${failed.reason}",
                    failed = failed,
                )
            }
            if (meta.directedAtBot && message.allowsChatRouting(meta)) {
                return heuristic(
                    intent = RouteIntent.CHAT,
                    reason = "heuristic:survey_chat_with_bot; llm=${failed.reason}",
                    failed = failed,
                )
            }
            return heuristic(
                intent = RouteIntent.BUG,
                reason = "heuristic:survey_continuation; llm=${failed.reason}",
                failed = failed,
            )
        }

        if (bugLike) {
            return heuristic(
                intent = RouteIntent.BUG,
                reason = "heuristic:bug_report; llm=${failed.reason}",
                failed = failed,
            )
        }

        if (meta.replyToBot || meta.continuationWithBot) {
            return heuristic(
                intent = RouteIntent.CHAT,
                reason = "heuristic:continuation_with_bot; llm=${failed.reason}",
                failed = failed,
            )
        }

        if (meta.directedAtBot && message.allowsChatRouting(meta)) {
            return heuristic(
                intent = RouteIntent.CHAT,
                reason = "heuristic:directed_at_bot; llm=${failed.reason}",
                failed = failed,
            )
        }

        return failed
    }

    private fun heuristic(
        intent: RouteIntent,
        reason: String,
        failed: RouteDecision,
    ): RouteDecision =
        RouteDecision(
            intent = intent,
            confidence = 0.55,
            reason = reason,
            raw = failed.raw,
            model = "heuristic",
            parseOk = true,
        )
}
