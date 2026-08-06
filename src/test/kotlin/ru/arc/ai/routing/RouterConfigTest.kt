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
            routerConfig.prefilterEnabled shouldBe true
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

        "can disable deterministic prefilter for rollback" {
            val tmp = Files.createTempDirectory("router-config-prefilter")
            Files.writeString(
                tmp.resolve("assistant.yml"),
                """
                routing:
                  prefilter-enabled: false
                """.trimIndent(),
            )
            val config = Config(tmp, "assistant.yml")
            RouterConfig.from(config).prefilterEnabled shouldBe false
        }

        "parses bug observe-only mode" {
            val tmp = Files.createTempDirectory("router-config-bug-observe-only")
            Files.writeString(
                tmp.resolve("assistant.yml"),
                """
                bug:
                  observe-only: true
                """.trimIndent(),
            )
            val config = Config(tmp, "assistant.yml")
            RouterConfig.from(config).bugObserveOnly shouldBe true
        }
    }
})
