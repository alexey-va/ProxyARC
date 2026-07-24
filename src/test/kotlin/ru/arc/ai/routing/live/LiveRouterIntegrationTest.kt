package ru.arc.ai.routing.live

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Tag
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.routing.dispatch.RouteDecisionPolicy
import ru.arc.ai.routing.router.AssistantRouter
import ru.arc.ai.routing.router.OpenRouterRouterLlmGateway
import ru.arc.ai.routing.router.RouterConfig
import ru.arc.ai.routing.router.RouterPrompt

/**
 * Live integration: real OpenRouter HTTP. Skipped when OPENROUTER_API_KEY is unset.
 *
 * Run: ./gradlew liveTest
 */
@Tag("live")
class LiveRouterIntegrationTest : FreeSpec({
    val apiKey = System.getenv("OPENROUTER_API_KEY")?.trim().orEmpty()
    val runLive = System.getenv("RUN_LIVE_ROUTER_TESTS") == "true"
    if (!runLive || apiKey.isEmpty()) {
        "live router" - {
            "skipped — set RUN_LIVE_ROUTER_TESTS=true and OPENROUTER_API_KEY for ./gradlew liveTest" {
            }
        }
    } else {
        val llmConfig = LiveTestLlmConfig
        val llmClient = OpenRouterLlmClient.create(llmConfig)
        val routerModel =
            System.getenv("ROUTER_MODEL")?.trim().orEmpty().ifBlank { "deepseek/deepseek-v4-flash" }

        val routerConfig =
            RouterConfig(
                enabled = true,
                model = routerModel,
                fallbackModel = routerModel,
                temperature = 0.0,
                maxTokens = 256,
                maxContextLines = 15,
                maxRouteHistory = 5,
                continuationWindowSec = 90,
                observeFormat = "[%time% %delta%] %player% » %message%",
                timeoutSec = llmConfig.timeoutSeconds.toInt(),
                logSkipAtDebug = true,
                logRouteInfo = false,
                enabledIntents = setOf(ru.arc.ai.routing.router.RouteIntent.CHAT, ru.arc.ai.routing.router.RouteIntent.BUG),
                recentOpenTickets = 3,
            )

        val promptText =
            RouterPrompt::class.java.getResourceAsStream("/prompts/router.txt")!!
                .bufferedReader()
                .readText()
                .trim()
        val router =
            AssistantRouter(
                prompt = RouterPrompt.forTest(promptText),
                gateway = OpenRouterRouterLlmGateway(llmClient, routerConfig),
                config = routerConfig,
            )

        val cases = RouterLiveCaseLoader.load()

        "live router OpenRouter" - {
            llmClient.enabled shouldBe true

            cases.forEach { case ->
                "${case.id} → ${case.expected}" {
                    val context = RouterLiveCaseLoader.toContext(case)
                    val rawDecision = router.classify(context).join()
                    val decision =
                        RouteDecisionPolicy.apply(context.message, context.meta, rawDecision, routerConfig)

                    decision.parseOk shouldBe true
                    decision.raw.shouldNotBeBlank()
                    decision.intent shouldBe RouterLiveCaseLoader.expectedIntent(case)
                }
            }
        }
    }
})
