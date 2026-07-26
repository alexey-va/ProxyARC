package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import net.dv8tion.jda.api.entities.Message
import java.util.concurrent.Executor

class DiscordChatCleanerTest : FreeSpec({
    "keeps five newest messages and deletes older history" {
        val messages = List(8) { mockk<Message>(name = "message-$it") }
        val deleted = mutableListOf<Message>()
        val cleaner =
            DiscordChatCleaner(
                executor = Executor { it.run() },
                historyProvider = { messages },
                deleteMessage = deleted::add,
                deleteDelayMs = 0,
            )

        cleaner.start("chat")

        deleted shouldContainExactly messages.drop(5)
        cleaner.activeTaskCount() shouldBe 0
    }

    "stopped queued cleanup does not touch history" {
        val executor = QueuedExecutor()
        var providerCalls = 0
        val cleaner =
            DiscordChatCleaner(
                executor = executor,
                historyProvider = {
                    providerCalls++
                    null
                },
                deleteDelayMs = 0,
            )

        cleaner.start("chat")
        cleaner.stop("chat")
        executor.runAll()

        providerCalls shouldBe 0
        cleaner.activeTaskCount() shouldBe 0
    }

    "replacement cancels an older queued cleanup for the same channel" {
        val executor = QueuedExecutor()
        var providerCalls = 0
        val cleaner =
            DiscordChatCleaner(
                executor = executor,
                historyProvider = {
                    providerCalls++
                    null
                },
                deleteDelayMs = 0,
            )

        cleaner.start("chat")
        cleaner.start("chat")
        executor.runAll()

        providerCalls shouldBe 1
        cleaner.activeTaskCount() shouldBe 0
    }

    "close cancels queued tasks and rejects new cleanup" {
        val executor = QueuedExecutor()
        var providerCalls = 0
        val cleaner =
            DiscordChatCleaner(
                executor = executor,
                historyProvider = {
                    providerCalls++
                    null
                },
                deleteDelayMs = 0,
            )

        cleaner.start("chat")
        cleaner.close()
        cleaner.start("other")
        executor.runAll()

        providerCalls shouldBe 0
        cleaner.activeTaskCount() shouldBe 0
    }
})

private class QueuedExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        tasks.add(command)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().run()
        }
    }
}
