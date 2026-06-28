package ru.arc.ai.routing.router

import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.slf4j.LoggerFactory
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
                    val response = client.chat().completions().create(params)
                    response.choices().first().message().content().orElse("")
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
}
