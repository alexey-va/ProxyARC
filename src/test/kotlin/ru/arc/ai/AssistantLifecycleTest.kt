package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.memory.AssistantMemoryStore
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class AssistantLifecycleTest : FreeSpec({
    "requests are serialized and every caller completes" {
        withAssistant { assistant, launched ->
            val first = assistant.tryEnqueue("one", "first")
            val second = assistant.tryEnqueue("two", "second")

            launched.size shouldBe 1
            launched[0].complete(AssistantEnqueueResult.reply("one"))
            first.join().reply shouldBe "one"
            launched.size shouldBe 2

            launched[1].complete(AssistantEnqueueResult.reply("two"))
            second.join().reply shouldBe "two"
        }
    }

    "reload cancels the active request and completes pending callers" {
        withAssistant { assistant, launched ->
            val active = assistant.tryEnqueue("one", "first")
            val pending = assistant.tryEnqueue("two", "second")

            assistant.reload()

            launched.single().isCancelled shouldBe true
            active.join().skipReason shouldBe SkipReason.BUSY
            active.join().detail shouldBe "reloaded"
            pending.join().skipReason shouldBe SkipReason.BUSY
            pending.join().detail shouldBe "reloaded"
        }
    }

    "close drains the queue and rejects later requests" {
        withAssistant(closeAfter = false) { assistant, launched ->
            val active = assistant.tryEnqueue("one", "first")
            val pending = assistant.tryEnqueue("two", "second")

            assistant.close()

            launched.single().isCancelled shouldBe true
            active.join().detail shouldBe "closed"
            pending.join().detail shouldBe "closed"
            assistant.tryEnqueue("three", "third").join().skipReason shouldBe SkipReason.DISABLED
        }
    }

    "request launcher failure completes the caller with LLM error" {
        withAssistant(
            launcher = AssistantRequestLauncher { _, _, _ ->
                error("boom")
            },
        ) { assistant, _ ->
            val result = assistant.tryEnqueue("one", "first").join()

            result.skipReason shouldBe SkipReason.LLM_ERROR
            result.detail shouldBe "boom"
        }
    }
})

private fun withAssistant(
    closeAfter: Boolean = true,
    launcher: AssistantRequestLauncher? = null,
    block: (Assistant, MutableList<CompletableFuture<AssistantEnqueueResult>>) -> Unit,
) {
    val dir = Files.createTempDirectory("assistant-lifecycle-test")
    val launched = mutableListOf<CompletableFuture<AssistantEnqueueResult>>()
    val actualLauncher =
        launcher ?: AssistantRequestLauncher { _, _, _ ->
            CompletableFuture<AssistantEnqueueResult>().also(launched::add)
        }
    val config =
        ConfigManager.of(dir, "assistant.yml").also {
            it.setBoolean("chat.enabled", true)
            it.setInt("chat.max-pending-jobs", 4)
            it.setInt("chat.max-queue-age-sec", 60)
        }
    val assistant =
        Assistant(
            config = config,
            type = "chat",
            llmClient = mockk<OpenRouterLlmClient>(relaxed = true),
            memoryStore = AssistantMemoryStore(null),
            requestExecutor = Executor { command -> command.run() },
            requestLauncher = actualLauncher,
        )
    try {
        block(assistant, launched)
    } finally {
        if (closeAfter) assistant.close()
        dir.toFile().deleteRecursively()
    }
}
