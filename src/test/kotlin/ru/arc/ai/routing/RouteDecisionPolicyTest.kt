package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.dispatch.RouteDecisionPolicy
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.router.RouteDecision
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.router.RouterConfig
import ru.arc.ai.routing.survey.BugSurveySessionStore
import ru.arc.ai.tickets.IssueTicket
import ru.arc.ai.tickets.IssueTicketStore

class RouteDecisionPolicyTest : FreeSpec({
    beforeEach {
        BugSurveySessionStore.clear()
        IssueTicketStore.bind(null)
        IssueTicketStore.save(
            IssueTicket(
                ticketId = "RB-00099",
                threadId = "t1",
                starterMessageId = "m1",
                reporter = "MoonLint",
                title = "test",
                createdAt = 1L,
            ),
        )
    }
    afterEach {
        BugSurveySessionStore.clear()
        IssueTicketStore.bind(null)
    }

    "RouteDecisionPolicy" - {
        "allows explicitly addressed chat without ! in game" {
            val config = defaultConfig()
            val message =
                InboundMessage(
                    player = "grocer",
                    rawText = "скорен ты тут",
                    displayText = "скорен ты тут",
                    timestampMs = 1L,
                    server = "survival",
                    source = InboundMessage.Source.GAME,
                )
            val decision =
                RouteDecisionPolicy.apply(
                    message,
                    InboundMeta(
                        directedAtBot = true,
                        replyToBot = false,
                        continuationWithBot = false,
                        secondsSinceBot = null,
                        replyToPlayer = null,
                    ),
                    RouteDecision(
                        intent = RouteIntent.CHAT,
                        confidence = 0.9,
                        reason = "обращение",
                        raw = "{}",
                    ),
                    config,
                )
            decision.intent shouldBe RouteIntent.CHAT
            decision.reason shouldBe "обращение"
        }

        "allows a same-player continuation without another ! prefix" {
            val config = defaultConfig()
            val message =
                InboundMessage(
                    player = "grocer",
                    rawText = "как попасть на выживание",
                    displayText = "как попасть на выживание",
                    timestampMs = 1L,
                    server = "survival",
                    source = InboundMessage.Source.GAME,
                )
            val decision =
                RouteDecisionPolicy.apply(
                    message,
                    InboundMeta(
                        directedAtBot = false,
                        replyToBot = true,
                        continuationWithBot = true,
                        secondsSinceBot = 4,
                        replyToPlayer = null,
                    ),
                    RouteDecision(
                        intent = RouteIntent.CHAT,
                        confidence = 0.9,
                        reason = "continuation",
                        raw = "{}",
                    ),
                    config,
                )
            decision.intent shouldBe RouteIntent.CHAT
        }

        "allows chat with ! in game" {
            val config = defaultConfig()
            val message =
                InboundMessage(
                    player = "grocer",
                    rawText = "!скорен ты тут",
                    displayText = "скорен ты тут",
                    timestampMs = 1L,
                    server = "survival",
                    source = InboundMessage.Source.GAME,
                )
            val decision =
                RouteDecisionPolicy.apply(
                    message,
                    InboundMeta(
                        directedAtBot = false,
                        replyToBot = false,
                        continuationWithBot = false,
                        secondsSinceBot = null,
                        replyToPlayer = null,
                    ),
                    RouteDecision(
                        intent = RouteIntent.CHAT,
                        confidence = 0.9,
                        reason = "обращение",
                        raw = "{}",
                    ),
                    config,
                )
            decision.intent shouldBe RouteIntent.CHAT
        }

        "allows bug without ! in game" {
            val config = defaultConfig()
            val message =
                InboundMessage(
                    player = "metal",
                    rawText = "rtp не работает",
                    displayText = "rtp не работает",
                    timestampMs = 1L,
                    server = "survival",
                    source = InboundMessage.Source.GAME,
                )
            val decision =
                RouteDecisionPolicy.apply(
                    message,
                    InboundMeta(
                        directedAtBot = false,
                        replyToBot = false,
                        continuationWithBot = false,
                        secondsSinceBot = null,
                        replyToPlayer = null,
                    ),
                    RouteDecision(
                        intent = RouteIntent.BUG,
                        confidence = 0.9,
                        reason = "баг",
                        raw = "{}",
                    ),
                    config,
                )
            decision.intent shouldBe RouteIntent.BUG
        }

        "survey primary overrides router skip to bug" {
            val config = defaultConfig()
            BugSurveySessionStore.openOrTouch("GrocerMC")
            val message =
                InboundMessage(
                    player = "GrocerMC",
                    rawText = "в меню написано скорен лох",
                    displayText = "в меню написано скорен лох",
                    timestampMs = 1L,
                    server = "survival",
                    source = InboundMessage.Source.GAME,
                )
            val decision =
                RouteDecisionPolicy.apply(
                    message,
                    InboundMeta(
                        directedAtBot = false,
                        replyToBot = true,
                        continuationWithBot = false,
                        secondsSinceBot = 5,
                        replyToPlayer = null,
                    ),
                    RouteDecision(
                        intent = RouteIntent.SKIP,
                        confidence = 0.95,
                        reason = "шутка",
                        raw = "{}",
                    ),
                    config,
                )
            decision.intent shouldBe RouteIntent.BUG
            decision.reason shouldBe "survey_primary_not_skip; шутка"
        }

        "survey primary does not override skip for offtopic smalltalk" {
            val config = defaultConfig()
            BugSurveySessionStore.openOrTouch("Dorfik")
            val message =
                InboundMessage(
                    player = "Dorfik",
                    rawText = "скорен а ты видел новый спавн?",
                    displayText = "скорен а ты видел новый спавн?",
                    timestampMs = 1L,
                    server = "survival",
                    source = InboundMessage.Source.GAME,
                )
            val decision =
                RouteDecisionPolicy.apply(
                    message,
                    InboundMeta(
                        directedAtBot = true,
                        replyToBot = false,
                        continuationWithBot = false,
                        secondsSinceBot = null,
                        replyToPlayer = null,
                    ),
                    RouteDecision(
                        intent = RouteIntent.SKIP,
                        confidence = 0.95,
                        reason = "не баг",
                        raw = "{}",
                    ),
                    config,
                )
            decision.intent shouldBe RouteIntent.SKIP
        }

        "survey primary keeps explicit prefixed off topic chat out of bug intake" {
            val config = defaultConfig()
            BugSurveySessionStore.openOrTouch("Dorfik")
            val message =
                InboundMessage(
                    player = "Dorfik",
                    rawText = "!скорен а ты видел новый спавн?",
                    displayText = "скорен а ты видел новый спавн?",
                    timestampMs = 1L,
                    server = "survival",
                    source = InboundMessage.Source.GAME,
                )
            val decision =
                RouteDecisionPolicy.apply(
                    message,
                    InboundMeta(
                        directedAtBot = true,
                        replyToBot = true,
                        continuationWithBot = true,
                        secondsSinceBot = 5,
                        replyToPlayer = null,
                    ),
                    RouteDecision(
                        intent = RouteIntent.CHAT,
                        confidence = 0.95,
                        reason = "prefilter:survey_direct_chat",
                        raw = "",
                        model = "prefilter",
                    ),
                    config,
                )
            decision.intent shouldBe RouteIntent.CHAT
        }

        "open ticket close request overrides router chat to bug" {
            val config = defaultConfig()
            val message =
                InboundMessage(
                    player = "MoonLint",
                    rawText = "!ладно починилось закрой тикет",
                    displayText = "ладно починилось закрой тикет",
                    timestampMs = 1L,
                    server = "classic",
                    source = InboundMessage.Source.GAME,
                )
            val decision =
                RouteDecisionPolicy.apply(
                    message,
                    InboundMeta(
                        directedAtBot = false,
                        replyToBot = true,
                        continuationWithBot = true,
                        secondsSinceBot = 5,
                        replyToPlayer = null,
                    ),
                    RouteDecision(
                        intent = RouteIntent.CHAT,
                        confidence = 0.95,
                        reason = "continuation",
                        raw = "{}",
                    ),
                    config,
                )
            decision.intent shouldBe RouteIntent.BUG
            decision.reason shouldBe "open_ticket_resolved_or_close; continuation"
        }

        "vague есть бага overrides router skip to bug" {
            val config = defaultConfig()
            val message =
                InboundMessage(
                    player = "NovaShard",
                    rawText = "есть бага",
                    displayText = "есть бага",
                    timestampMs = 1L,
                    server = "classic",
                    source = InboundMessage.Source.GAME,
                )
            val decision =
                RouteDecisionPolicy.apply(
                    message,
                    InboundMeta(
                        directedAtBot = false,
                        replyToBot = false,
                        continuationWithBot = false,
                        secondsSinceBot = null,
                        replyToPlayer = null,
                    ),
                    RouteDecision(
                        intent = RouteIntent.SKIP,
                        confidence = 0.8,
                        reason = "vague",
                        raw = "{}",
                    ),
                    config,
                )
            decision.intent shouldBe RouteIntent.BUG
            decision.reason shouldBe "bug_like_not_skip; vague"
        }

        "general bug discussion does not override a local skip" {
            val message =
                InboundMessage(
                    player = "Talker",
                    rawText = "а баги щас какие есть кто знает",
                    displayText = "а баги щас какие есть кто знает",
                    timestampMs = 1L,
                    server = "classic",
                    source = InboundMessage.Source.GAME,
                )
            val decision =
                RouteDecisionPolicy.apply(
                    message,
                    InboundMeta(false, false, false, null, null),
                    RouteDecision(RouteIntent.SKIP, 0.9, "prefilter", "{}"),
                    defaultConfig(),
                )
            decision.intent shouldBe RouteIntent.SKIP
        }

        "observe-only silences a concrete bug report" {
            val decision =
                RouteDecisionPolicy.apply(
                    message("Tester", "rtp не работает"),
                    InboundMeta(false, false, false, null, null),
                    RouteDecision(RouteIntent.BUG, 0.98, "prefilter:clear_bug", ""),
                    defaultConfig(bugObserveOnly = true),
                )

            decision.intent shouldBe RouteIntent.SKIP
            decision.reason shouldBe "bug_observe_only; prefilter:clear_bug"
        }

        "observe-only silences a vague directed bug claim" {
            val decision =
                RouteDecisionPolicy.apply(
                    message("Tester", "скорен, есть бага"),
                    InboundMeta(true, false, false, null, null),
                    RouteDecision(RouteIntent.CHAT, 0.98, "prefilter:directed_chat", ""),
                    defaultConfig(bugObserveOnly = true),
                )

            decision.intent shouldBe RouteIntent.SKIP
            decision.reason shouldBe "bug_observe_only; prefilter:directed_chat"
        }

        "observe-only keeps an ordinary direct question in chat" {
            val decision =
                RouteDecisionPolicy.apply(
                    message("Tester", "скорен, как попасть на выживание?"),
                    InboundMeta(true, false, false, null, null),
                    RouteDecision(RouteIntent.CHAT, 0.98, "prefilter:directed_chat", ""),
                    defaultConfig(bugObserveOnly = true),
                )

            decision.intent shouldBe RouteIntent.CHAT
        }

        "observe-only ignores a request to interrogate about a bug" {
            val decision =
                RouteDecisionPolicy.apply(
                    message("Tester", "скорен, баг, расспроси меня подробнее"),
                    InboundMeta(true, false, false, null, null),
                    RouteDecision(RouteIntent.CHAT, 0.98, "prefilter:directed_chat", ""),
                    defaultConfig(bugObserveOnly = true),
                )

            decision.intent shouldBe RouteIntent.SKIP
            decision.reason shouldBe "bug_observe_only; prefilter:directed_chat"
        }

        "observe-only silences details from a legacy active survey" {
            BugSurveySessionStore.openOrTouch("Tester")
            val decision =
                RouteDecisionPolicy.apply(
                    message("Tester", "после релога всё равно не работает"),
                    InboundMeta(false, true, true, 3, null),
                    RouteDecision(RouteIntent.CHAT, 0.9, "continuation", ""),
                    defaultConfig(bugObserveOnly = true),
                )

            decision.intent shouldBe RouteIntent.SKIP
            decision.reason shouldBe "bug_observe_only; survey_primary_continuation; continuation"
        }
    }
})

private fun message(
    player: String,
    text: String,
): InboundMessage =
    InboundMessage(
        player = player,
        rawText = text,
        displayText = text,
        timestampMs = 1L,
        server = "survival",
        source = InboundMessage.Source.GAME,
    )

private fun defaultConfig(bugObserveOnly: Boolean = false): RouterConfig =
    RouterConfig(
        enabled = true,
        model = "test",
        fallbackModel = "test",
        temperature = 0.0,
        maxTokens = 120,
        maxContextLines = 15,
        maxRouteHistory = 5,
        continuationWindowSec = 90,
        observeFormat = "[%time%] %player% » %message%",
        timeoutSec = 15,
        logSkipAtDebug = true,
        logRouteInfo = true,
        enabledIntents = setOf(RouteIntent.CHAT, RouteIntent.BUG),
        recentOpenTickets = 3,
        bugObserveOnly = bugObserveOnly,
    )
