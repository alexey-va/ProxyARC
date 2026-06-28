package ru.arc.ai.routing.router

import com.openai.models.chat.completions.ChatCompletionCreateParams
import ru.arc.ai.llm.OpenRouterLlmClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class OpenRouterRouterLlmGateway(
    private val llmClient: OpenRouterLlmClient,
    private val config: RouterConfig,
) : RouterLlmGateway {
    override fun complete(
        systemPrompt: String,
        userContent: String,
        model: String,
    ): CompletableFuture<String> {
        val client = llmClient.client
        if (!llmClient.enabled || client == null) {
            return CompletableFuture.failedFuture(IllegalStateException("LLM not ready"))
        }
        return CompletableFuture.supplyAsync {
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
        }.orTimeout(config.timeoutSec.toLong(), TimeUnit.SECONDS)
    }
}
