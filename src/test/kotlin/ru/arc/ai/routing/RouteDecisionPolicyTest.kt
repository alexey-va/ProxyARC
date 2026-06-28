package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.dispatch.RouteDecisionPolicy
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.router.RouteDecision
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouterConfig

class RouteDecisionPolicyTest : FreeSpec({
    "RouteDecisionPolicy" - {
        "blocks chat without ! in game" {
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
                    RouteDecision(
                        intent = RouteIntent.CHAT,
                        confidence = 0.9,
                        reason = "обращение",
                        raw = "{}",
                    ),
                    config,
                )
            decision.intent shouldBe RouteIntent.SKIP
            decision.reason shouldBe "chat_requires_!_prefix; обращение"
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
    }
})

private fun defaultConfig(): RouterConfig =
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
    )
