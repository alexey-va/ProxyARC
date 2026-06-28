package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouterConfig
import ru.arc.config.Config
import java.nio.file.Files

class RouterConfigTest : FreeSpec({
    "RouterConfig" - {
        "defaults chat and bug enabled" {
            val tmp = Files.createTempDirectory("router-config")
            val config = Config(tmp, "assistant.yml")
            val routerConfig = RouterConfig.from(config)
            routerConfig.isIntentEnabled(RouteIntent.CHAT) shouldBe true
            routerConfig.isIntentEnabled(RouteIntent.BUG) shouldBe true
            routerConfig.isIntentEnabled(RouteIntent.SKIP) shouldBe true
        }

        "parses enabled-intents list" {
            val tmp = Files.createTempDirectory("router-config-list")
            Files.writeString(
                tmp.resolve("assistant.yml"),
                """
                routing:
                  enabled-intents:
                    - chat
                """.trimIndent(),
            )
            val config = Config(tmp, "assistant.yml")
            val routerConfig = RouterConfig.from(config)
            routerConfig.isIntentEnabled(RouteIntent.CHAT) shouldBe true
            routerConfig.isIntentEnabled(RouteIntent.BUG) shouldBe false
        }
    }
})
