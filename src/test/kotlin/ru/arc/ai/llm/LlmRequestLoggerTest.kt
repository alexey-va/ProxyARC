package ru.arc.ai.llm

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.string.shouldContain
import com.openai.models.completions.CompletionUsage

class LlmRequestLoggerTest : FreeSpec({
    "formatUsage includes cache ratio when present" {
        val usage =
            CompletionUsage
                .builder()
                .promptTokens(1000)
                .completionTokens(12)
                .totalTokens(1012)
                .promptTokensDetails(
                    CompletionUsage.PromptTokensDetails
                        .builder()
                        .cachedTokens(800)
                        .build(),
                ).build()
        LlmRequestLogger.formatUsage(usage) shouldContain "prompt=1000"
        LlmRequestLogger.formatUsage(usage) shouldContain "cached=800(80%)"
        LlmRequestLogger.formatUsage(usage) shouldContain "completion=12"
    }

    "formatUsage without cache" {
        val usage =
            CompletionUsage
                .builder()
                .promptTokens(500)
                .completionTokens(20)
                .totalTokens(520)
                .build()
        LlmRequestLogger.formatUsage(usage) shouldContain "prompt=500 completion=20"
    }
})
