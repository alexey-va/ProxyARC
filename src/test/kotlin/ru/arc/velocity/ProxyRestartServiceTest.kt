package ru.arc.velocity

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import net.kyori.adventure.text.Component
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.TestTaskScheduler
import java.time.Duration

class ProxyRestartServiceTest : FreeSpec({
    "countdown warns online players and performs graceful shutdown" {
        val scheduler = TestTaskScheduler()
        val broadcasts = mutableListOf<Component>()
        val shutdownReasons = mutableListOf<Component>()
        val events = mutableListOf<String>()
        val service =
            ProxyRestartService(
                scheduler = scheduler,
                playerCount = { 3 },
                broadcast = broadcasts::add,
                shutdown = shutdownReasons::add,
                eventLog = events::add,
            )

        val result = service.schedule(Duration.ofSeconds(10), "console")

        result shouldBe
            ProxyRestartScheduleResult.Scheduled(
                ProxyRestartPlan(Duration.ofSeconds(10), "console", 3),
            )
        broadcasts.size shouldBe 1
        scheduler.advanceMs(5_000)
        broadcasts.size shouldBe 2
        scheduler.advanceMs(5_000)
        shutdownReasons.size shouldBe 1
        service.pendingPlan() shouldBe null
        events.last() shouldBe "shutdown delay=10s players=3 by=console"
    }

    "zero-player countdown stays quiet but still shuts down" {
        val scheduler = TestTaskScheduler()
        val broadcasts = mutableListOf<Component>()
        var shutdowns = 0
        val service =
            ProxyRestartService(
                scheduler = scheduler,
                playerCount = { 0 },
                broadcast = broadcasts::add,
                shutdown = { shutdowns++ },
            )

        service.schedule(Duration.ofSeconds(5), "console")
        scheduler.advanceMs(5_000)

        broadcasts shouldContainExactly emptyList()
        shutdowns shouldBe 1
    }

    "a second countdown is rejected and cancellation removes scheduled work" {
        val scheduler = TestTaskScheduler()
        var shutdowns = 0
        val service =
            ProxyRestartService(
                scheduler = scheduler,
                playerCount = { 1 },
                broadcast = {},
                shutdown = { shutdowns++ },
            )

        service.schedule(Duration.ofSeconds(30), "first")
        service.schedule(Duration.ofSeconds(10), "second") shouldBe ProxyRestartScheduleResult.AlreadyPending
        service.cancel("console") shouldBe true
        service.cancel("console") shouldBe false
        scheduler.advanceMs(30_000)

        shutdowns shouldBe 0
    }

    "an old callback cannot shut down a replacement countdown after cancellation" {
        val callbacks = mutableListOf<Runnable>()
        val task = mockk<ScheduledTask>(relaxed = true)
        val scheduler = mockk<TaskScheduler>()
        every { scheduler.runLater(any(), any()) } answers {
            callbacks += secondArg<Runnable>()
            task
        }
        var shutdowns = 0
        val service =
            ProxyRestartService(
                scheduler = scheduler,
                playerCount = { 0 },
                broadcast = {},
                shutdown = { shutdowns++ },
            )

        service.schedule(Duration.ofSeconds(5), "first")
        val staleFinalCallback = callbacks.last()
        service.cancel("console") shouldBe true
        service.schedule(Duration.ofSeconds(30), "second")
        staleFinalCallback.run()

        shutdowns shouldBe 0
        service.pendingPlan()?.initiatedBy shouldBe "second"
    }

    "duration parser enforces operational bounds" {
        ProxyRestartDuration.parse("30") shouldBe Duration.ofSeconds(30)
        ProxyRestartDuration.parse("3m") shouldBe Duration.ofMinutes(3)
        ProxyRestartDuration.parse("1h") shouldBe null
        ProxyRestartDuration.parse("4s") shouldBe null
        ProxyRestartDuration.parse("9223372036854775807h") shouldBe null
        ProxyRestartDuration.parse("nonsense") shouldBe null
    }

    "console command schedules and cancels the proxy countdown" {
        val scheduler = TestTaskScheduler()
        val service =
            ProxyRestartService(
                scheduler = scheduler,
                playerCount = { 2 },
                broadcast = {},
                shutdown = {},
            )
        val source =
            mockk<CommandSource>(relaxed = true) {
                every { hasPermission("arc.admin") } returns true
            }
        val invocation = mockk<SimpleCommand.Invocation>()
        every { invocation.source() } returns source
        every { invocation.arguments() } returns arrayOf("restart", "-delay", "10s")
        Velocity.proxyRestartService = service

        ProxyARCCommand().execute(invocation)
        service.pendingPlan()?.delay shouldBe Duration.ofSeconds(10)

        every { invocation.arguments() } returns arrayOf("restart", "cancel")
        ProxyARCCommand().execute(invocation)
        service.pendingPlan() shouldBe null
        Velocity.proxyRestartService = null
    }
})
