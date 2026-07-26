package ru.arc.ai.routing.router

import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.slf4j.LoggerFactory
import ru.arc.ai.llm.LlmRequestLogger
import ru.arc.ai.llm.OpenRouterLlmClient
import java.util.UUID
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

        val requestId = UUID.randomUUID().toString()
        LlmRequestLogger.logRouterRequestStart(
            log = log,
            requestId = requestId,
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
                            .maxCompletionTokens(config.maxTokens.toLong())
                            .build()
                    val startedNs = System.nanoTime()
                    val response = client.chat().completions().create(params)
                    val latencyMs = (System.nanoTime() - startedNs) / 1_000_000
                    LlmRequestLogger.logRouterRequestComplete(
                        log = log,
                        requestId = requestId,
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
                            choice.finishReason().toString(),
                        )
                    }
                    content
                } catch (e: Exception) {
                    log.warn(
                        "LLM error requestId={} agent=router mode=route source=unknown depth=0 model={} outcome=error errorType={} message={}",
                        requestId,
                        model,
                        e.javaClass.simpleName,
                        ru.arc.ai.llm.LogPreview.of(RouteLog.describeError(e), 160),
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
