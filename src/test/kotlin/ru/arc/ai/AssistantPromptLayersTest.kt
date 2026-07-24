package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.Logger
import ru.arc.ai.memory.AssistantMemoryStore
import ru.arc.config.ConfigManager
import java.nio.file.Files

class AssistantPromptLayersTest : FreeSpec({

    "staticSystemPrompt is trimmed and unchanged" {
        AssistantPromptLayers.staticSystemPrompt("  hello\n") shouldBe "hello"
    }

    "memoryContextMessage is separate user block, not appended to system" {
        val dir = Files.createTempDirectory("assistant-prompt-test")
        try {
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  memory:
                    enabled: true
                    min-confidence: 0.5
                    max-injected: 20
                    prompt-header: "facts:"
                """.trimIndent(),
            )
            val config = ConfigManager.of(dir, "assistant.yml")
            val store = AssistantMemoryStore(null)
            store.remember("grocermc", "любит алмазы", 0.9)

            val msg = AssistantPromptLayers.memoryContextMessage(config, "chat", store)!!
            val content = msg.asUser().content().asText()
            content.startsWith("facts:\n- [") shouldBe true
            content shouldContain "grocermc: любит алмазы"
            msg.asUser().name().orElse("") shouldBe AssistantPromptLayers.MEMORY_MESSAGE_NAME
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    "system prompt must not contain injected facts" {
        val static = AssistantPromptLayers.staticSystemPrompt("base rules only")
        static shouldNotContain "запомненные факты"
        static shouldNotContain "grocermc"
    }

    "logCompletionUsage delegates to formatUsage at debug" {
        val log = mockk<Logger>(relaxed = true)
        val usage =
            com.openai.models.completions.CompletionUsage
                .builder()
                .promptTokens(1000)
                .completionTokens(12)
                .totalTokens(1012)
                .promptTokensDetails(
                    com.openai.models.completions.CompletionUsage.PromptTokensDetails
                        .builder()
                        .cachedTokens(800)
                        .build(),
                ).build()
        AssistantPromptLayers.logCompletionUsage(log, "deepseek/deepseek-v4-flash", usage)
        verify {
            log.debug(
                match<String> { it.contains("LLM usage") },
                "deepseek/deepseek-v4-flash",
                match<String> { it.contains("cached=800") },
            )
        }
    }
})
