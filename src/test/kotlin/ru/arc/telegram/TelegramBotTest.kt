package ru.arc.telegram

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.generics.BotSession
import ru.arc.core.TestTaskScheduler
import java.util.concurrent.atomic.AtomicInteger

class TelegramBotTest : FreeSpec({
    "updates without a message are ignored" {
        val bot = TelegramBot(token = "test", scheduler = TestTaskScheduler())

        shouldNotThrowAny {
            bot.onUpdateReceived(Update())
        }
        bot.close()
    }

    "outbound requests use the managed scheduler and stop after close" {
        val scheduler = TestTaskScheduler()
        val executions = AtomicInteger()
        val bot =
            TelegramBot(
                token = "test",
                scheduler = scheduler,
                requestExecutor = { executions.incrementAndGet() },
            )

        bot.enqueue(SendMessage())
        executions.get() shouldBe 0
        scheduler.executeImmediate()
        executions.get() shouldBe 1

        bot.enqueue(SendMessage())
        bot.close()
        scheduler.executeImmediate()
        executions.get() shouldBe 1
    }

    "runtime stops the registered session and closes the bot" {
        val scheduler = TestTaskScheduler()
        val executions = AtomicInteger()
        val session =
            mockk<BotSession>(relaxed = true) {
                every { isRunning } returns true
            }
        val bot =
            TelegramBot(
                token = "test",
                scheduler = scheduler,
                requestExecutor = { executions.incrementAndGet() },
            )
        val runtime = TelegramRuntime { session }

        runtime.start(bot)
        runtime.close()
        runtime.close()
        bot.enqueue(SendMessage())
        scheduler.executeImmediate()

        verify(exactly = 1) { session.stop() }
        executions.get() shouldBe 0
    }

    "failed registration closes the rejected bot" {
        val scheduler = TestTaskScheduler()
        val executions = AtomicInteger()
        val bot =
            TelegramBot(
                token = "test",
                scheduler = scheduler,
                requestExecutor = { executions.incrementAndGet() },
            )
        val runtime =
            TelegramRuntime {
                throw IllegalStateException("registration failed")
            }

        runCatching { runtime.start(bot) }.isFailure shouldBe true

        bot.enqueue(SendMessage())
        scheduler.executeImmediate()
        executions.get() shouldBe 0
    }

    "starting a replacement stops the previous session" {
        val firstSession =
            mockk<BotSession>(relaxed = true) {
                every { isRunning } returns true
            }
        val secondSession = mockk<BotSession>(relaxed = true)
        val sessions = ArrayDeque(listOf(firstSession, secondSession))
        val runtime = TelegramRuntime { sessions.removeFirst() }
        val firstBot = TelegramBot(token = "first", scheduler = TestTaskScheduler())
        val secondBot = TelegramBot(token = "second", scheduler = TestTaskScheduler())

        runtime.start(firstBot)
        runtime.start(secondBot)

        verify(exactly = 1) { firstSession.stop() }
        runtime.close()
    }
})
