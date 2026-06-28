package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouterJsonParser

class RouterJsonParserTest : FreeSpec({
    "RouterJsonParser" - {
        "parses plain json" {
            val result =
                RouterJsonParser.parse(
                    """{"intent":"chat","confidence":0.95,"reason":"обращение"}""",
                )
            result.intent shouldBe RouteIntent.CHAT
            result.confidence shouldBe 0.95
            result.parseOk shouldBe true
        }

        "parses bug intent" {
            val result =
                RouterJsonParser.parse(
                    """{"intent":"bug","confidence":0.9,"reason":"rtp"}""",
                )
            result.intent shouldBe RouteIntent.BUG
        }

        "maps legacy bug_new to bug" {
            val result =
                RouterJsonParser.parse(
                    """{"intent":"bug_new","confidence":0.9,"reason":"rtp"}""",
                )
            result.intent shouldBe RouteIntent.BUG
        }

        "parses fenced json" {
            val result =
                RouterJsonParser.parse(
                    """```json
{"intent":"bug","confidence":0.9,"reason":"rtp"}
```""",
                )
            result.intent shouldBe RouteIntent.BUG
            result.parseOk shouldBe true
        }

        "empty response fails to skip" {
            val result = RouterJsonParser.parse("")
            result.intent shouldBe RouteIntent.SKIP
            result.parseOk shouldBe false
        }
    }
})
