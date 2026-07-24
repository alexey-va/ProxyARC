package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.string.shouldContain
import ru.arc.ai.routing.context.RouterContextBuilder
import ru.arc.ai.routing.history.RouteHistoryStore
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.observe.ChatLog
import ru.arc.ai.routing.router.RouterConfig
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.config.Config
import java.nio.file.Files

class RouterContextBuilderTest : FreeSpec({
    "RouterContextBuilder" - {
        "includes chat and route history" {
            val tmp = Files.createTempDirectory("router-ctx")
            val configFile = tmp.resolve("assistant.yml")
            Files.writeString(
                configFile,
                """
                routing:
                  max-context-lines: 10
                  max-route-history: 5
                """.trimIndent(),
            )
            val config = Config(tmp, "assistant.yml")
            val routerConfig = RouterConfig.from(config)
            val chatLog = ChatLog(routerConfig.maxContextLines)
            chatLog.append("[12:00:00 +5s] metal » че как")
            val routeHistory = RouteHistoryStore(routerConfig.maxRouteHistory)
            routeHistory.append("grocer", RouteIntent.BUG, "rtp не работает", 0.9, 1L)
            val builder = RouterContextBuilder(chatLog, routeHistory, routerConfig)
            val message =
                InboundMessage(
                    player = "grocer",
                    rawText = "скорен ты тут",
                    displayText = "скорен ты тут",
                    timestampMs = 1000L,
                    server = "survival",
                    source = InboundMessage.Source.GAME,
                )
            val meta =
                InboundMeta(
                    directedAtBot = true,
                    replyToBot = false,
                    continuationWithBot = false,
                    secondsSinceBot = null,
                    replyToPlayer = null,
                )
            val content = builder.build(message, meta).toUserContent()
            content shouldContain "player=grocer"
            content shouldContain "world=мир биомов"
            content shouldContain "recent_chat:"
            content shouldContain "metal » че как"
            content shouldContain "recent_routes:"
            content shouldContain "bug"
        }
    }
})
