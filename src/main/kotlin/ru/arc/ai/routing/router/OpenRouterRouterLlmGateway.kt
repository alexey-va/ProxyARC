package ru.arc.ai.routing.router

import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.slf4j.LoggerFactory
import ru.arc.ai.llm.LlmRequestLogger
import ru.arc.ai.llm.OpenRouterLlmClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class OpenRouterRouterLlmGateway(
    private val llmClient: OpenRouterLlmClient,
    private val config: RouterConfig,
) : RouterLlmGateway {
    private val log = LoggerFactory.getLogger(OpenRouterRouterLlmGateway::class.java)

    override fun complete(
        systemPrompt: String,
        userContent: String,
        model: String,
        player: String?,
    ): CompletableFuture<String> {
        val client = llmClient.client
        if (!llmClient.enabled) {
            val msg = "OpenRouter client disabled (check modules/llm.yml api-key and network)"
            log.warn("Router gateway: {}", msg)
            return CompletableFuture.failedFuture(IllegalStateException(msg))
        }
        if (client == null) {
            val msg = "OpenRouter HTTP client is null (LLM module not initialized)"
            log.warn("Router gateway: {}", msg)
            return CompletableFuture.failedFuture(IllegalStateException(msg))
        }

        LlmRequestLogger.logRouterRequestStart(
            log = log,
            model = model,
            player = player,
            message = extractMessagePreview(userContent),
            userChars = systemPrompt.length + userContent.length,
        )

        return CompletableFuture
            .supplyAsync {
                try {
                    val params =
                        ChatCompletionCreateParams.builder()
                            .addSystemMessage(systemPrompt)
                            .addUserMessage(userContent)
                            .model(model)
                            .temperature(config.temperature)
                            .maxTokens(config.maxTokens.toLong())
                            .build()
                    val startedNs = System.nanoTime()
                    val response = client.chat().completions().create(params)
                    val latencyMs = (System.nanoTime() - startedNs) / 1_000_000
                    LlmRequestLogger.logRouterRequestComplete(
                        log = log,
                        model = model,
                        player = player,
                        latencyMs = latencyMs,
                        response = response,
                    )
                    val choice = response.choices().first()
                    val content = choice.message().content().orElse("")
                    if (content.isBlank()) {
                        log.warn(
                            "Router gateway empty content model={} finish_reason={}",
                            model,
                            choice.finishReason()?.toString() ?: "null",
                        )
                    }
                    content
                } catch (e: Exception) {
                    log.warn(
                        "Router gateway HTTP error model={}: {}",
                        model,
                        RouteLog.describeError(e),
                        e,
                    )
                    throw e
                }
            }.orTimeout(config.timeoutSec.toLong(), TimeUnit.SECONDS)
    }

    private fun extractMessagePreview(userContent: String): String? {
        return userContent
            .lineSequence()
            .firstOrNull { it.startsWith("message=") }
            ?.substringAfter("message=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
