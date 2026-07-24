package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.context.RouterContext
import ru.arc.ai.routing.history.RouteRecord
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.router.RouteDecision
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouterHeuristicFallback
import ru.arc.ai.routing.survey.BugSurveySession
import ru.arc.ai.routing.survey.BugSurveySessionStore
import ru.arc.ai.tickets.IssueTicket

class RouterHeuristicFallbackTest : FreeSpec({
    val failed =
        RouteDecision(
            intent = RouteIntent.SKIP,
            confidence = 0.0,
            reason = "empty response",
            raw = "",
            parseOk = false,
        )

    "continuation chat thread" - {
        val context =
            baseContext(
                displayText = "нет не починили",
                rawText = "!нет не починили",
                continuationWithBot = true,
                replyToBot = true,
            )
        val result = RouterHeuristicFallback.apply(context, failed)
        result.intent shouldBe RouteIntent.CHAT
        result.parseOk shouldBe true
        result.reason shouldBe "heuristic:continuation_with_bot; llm=empty response"
    }

    "continuation with open ticket still chat" - {
        val context =
            baseContext(
                displayText = "да, на survival",
                rawText = "да, на survival",
                continuationWithBot = true,
                replyToBot = true,
                openTicket =
                    IssueTicket(
                        ticketId = "RB-00001",
                        threadId = "t1",
                        starterMessageId = null,
                        reporter = "GrocerMC",
                        title = "rtp",
                        createdAt = 1L,
                    ),
            )
        val result = RouterHeuristicFallback.apply(context, failed)
        result.intent shouldBe RouteIntent.CHAT
    }

    "directed at bot with open ticket routes chat" - {
        val context =
            baseContext(
                displayText = "ку скорен",
                rawText = "!ку скорен",
                directedAtBot = true,
                openTicket =
                    IssueTicket(
                        ticketId = "RB-00001",
                        threadId = "t1",
                        starterMessageId = null,
                        reporter = "GrocerMC",
                        title = "rtp",
                        createdAt = 1L,
                    ),
            )
        val result = RouterHeuristicFallback.apply(context, failed)
        result.intent shouldBe RouteIntent.CHAT
    }

    "continuation after recent bug route still chat" - {
        val context =
            baseContext(
                displayText = "на survival",
                rawText = "на survival",
                continuationWithBot = true,
                replyToBot = true,
                recentRoutes =
                    listOf(
                        RouteRecord(
                            timestampMs = 1L,
                            intent = RouteIntent.BUG,
                            messageSnippet = "rtp не работает",
                            confidence = 0.9,
                        ),
                    ),
            )
        val result = RouterHeuristicFallback.apply(context, failed)
        result.intent shouldBe RouteIntent.CHAT
    }

    "continuation with active bug survey routes bug" - {
        BugSurveySessionStore.openOrTouch("GrocerMC")
        val context =
            baseContext(
                displayText = "на survival",
                rawText = "!на survival",
                continuationWithBot = true,
                replyToBot = true,
                activeBugSurvey = BugSurveySessionStore.get("GrocerMC"),
            )
        val result = RouterHeuristicFallback.apply(context, failed)
        result.intent shouldBe RouteIntent.BUG
        result.parseOk shouldBe true
        BugSurveySessionStore.clear()
    }

    "survey joke routes chat not bug" - {
        BugSurveySessionStore.openOrTouch("GrocerMC")
        val context =
            baseContext(
                displayText = "расскажи анекдот",
                rawText = "!расскажи анекдот",
                continuationWithBot = true,
                replyToBot = true,
                activeBugSurvey = BugSurveySessionStore.get("GrocerMC"),
            )
        val result = RouterHeuristicFallback.apply(context, failed)
        result.intent shouldBe RouteIntent.CHAT
        BugSurveySessionStore.clear()
    }

    "vague bug report routes bug when llm empty" - {
        val context =
            baseContext(
                displayText = "у меня баг",
                rawText = "у меня баг",
            )
        val result = RouterHeuristicFallback.apply(context, failed)
        result.intent shouldBe RouteIntent.BUG
        result.parseOk shouldBe true
    }

    "directed_at_bot with chat allowed" - {
        val context =
            baseContext(
                displayText = "скорен ку",
                rawText = "!скорен ку",
                directedAtBot = true,
            )
        val result = RouterHeuristicFallback.apply(context, failed)
        result.intent shouldBe RouteIntent.CHAT
        result.parseOk shouldBe true
    }

    "no signal keeps failed skip" - {
        val context =
            baseContext(
                displayText = "123",
                rawText = "123",
            )
        val result = RouterHeuristicFallback.apply(context, failed)
        result.intent shouldBe RouteIntent.SKIP
        result.parseOk shouldBe false
    }
})

private fun baseContext(
    displayText: String,
    rawText: String,
    directedAtBot: Boolean = false,
    replyToBot: Boolean = false,
    continuationWithBot: Boolean = false,
    openTicket: IssueTicket? = null,
    recentRoutes: List<RouteRecord> = emptyList(),
    activeBugSurvey: BugSurveySession? = null,
): RouterContext {
    val message =
        InboundMessage(
            player = "GrocerMC",
            rawText = rawText,
            displayText = displayText,
            timestampMs = 1L,
            server = "survival",
            source = InboundMessage.Source.GAME,
        )
    return RouterContext(
        message = message,
        meta =
            InboundMeta(
                directedAtBot = directedAtBot,
                replyToBot = replyToBot,
                continuationWithBot = continuationWithBot,
                secondsSinceBot = if (continuationWithBot) 10 else null,
                replyToPlayer = null,
            ),
        recentChat = emptyList(),
        openTicket = openTicket,
        recentOpenTickets = emptyList(),
        recentRoutes = recentRoutes,
        activeBugSurvey = activeBugSurvey,
    )
}
