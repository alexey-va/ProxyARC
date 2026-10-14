package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.Response
import ru.arc.config.ProxyConfigs
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DiscordConnectionServiceTest : FreeSpec({
    "initial REST transport failure retries off-thread and activates after recovery" {
        val config = discordConfig()
        val session = DiscordSession()
        val executor = mockk<ScheduledExecutorService>()
        val retryActions = mutableListOf<Runnable>()
        val retryDelays = mutableListOf<Long>()
        var attempts = 0
        val jda = readyJda()
        every { executor.execute(any()) } answers { firstArg<Runnable>().run() }
        every {
            executor.schedule(any<Runnable>(), any<Long>(), TimeUnit.MILLISECONDS)
        } answers {
            retryActions += firstArg<Runnable>()
            retryDelays += secondArg<Long>()
            mockk<ScheduledFuture<*>>(relaxed = true)
        }
        val service =
            DiscordConnectionService(config, session, executor) { _, _, _ ->
                attempts++
                if (attempts == 1) throw IOException("connect timed out")
                jda
            }
        var activations = 0

        service.start {
            activations++
            emptyList()
        }

        service.isEnabled() shouldBe true
        session.isReady() shouldBe false
        attempts shouldBe 1
        retryDelays shouldBe listOf(2_000L)

        retryActions.single().run()

        attempts shouldBe 2
        activations shouldBe 1
        session.isReady() shouldBe true
        service.isEnabled() shouldBe true
        service.close()
    }

    "authentication failure stops without retrying" {
        val config = discordConfig()
        val executor = mockk<ScheduledExecutorService>()
        val unauthorized =
            ErrorResponseException.create(
                ErrorResponse.UNKNOWN_TOKEN,
                Response(null, 401, "Unauthorized", 0L, emptySet()),
            )
        var attempts = 0
        every { executor.execute(any()) } answers { firstArg<Runnable>().run() }
        every {
            executor.schedule(any<Runnable>(), any<Long>(), TimeUnit.MILLISECONDS)
        } throws AssertionError("401 responses must not be retried")
        val service =
            DiscordConnectionService(config, DiscordSession(), executor) { _, _, _ ->
                attempts++
                throw unauthorized
            }

        service.start { emptyList() }

        attempts shouldBe 1
        service.isEnabled() shouldBe false
        service.close()
    }

    "close fences a pending Discord retry" {
        val config = discordConfig()
        val session = DiscordSession()
        val executor = mockk<ScheduledExecutorService>()
        val retryActions = mutableListOf<Runnable>()
        var attempts = 0
        every { executor.execute(any()) } answers { firstArg<Runnable>().run() }
        every {
            executor.schedule(any<Runnable>(), any<Long>(), TimeUnit.MILLISECONDS)
        } answers {
            retryActions += firstArg<Runnable>()
            mockk<ScheduledFuture<*>>(relaxed = true)
        }
        val service =
            DiscordConnectionService(config, session, executor) { _, _, _ ->
                attempts++
                throw IOException("connection refused")
            }

        service.start { emptyList() }
        attempts shouldBe 1
        retryActions.size shouldBe 1

        service.close()
        retryActions.single().run()

        attempts shouldBe 1
        session.isReady() shouldBe false
        service.isEnabled() shouldBe false
    }
})

private fun discordConfig() =
    ProxyConfigs.module(Files.createTempDirectory("discord-connection-runtime"), "discord.yml").also { config ->
        config.setBoolean("enabled", true)
        config.setString("token", "test-token")
        config.setString("channels.chat", "chat")
        config.setString("channels.general", "general")
    }

private fun readyJda(): JDA {
    val jda = mockk<JDA>(relaxed = true)
    val chat = mockk<TextChannel>(relaxed = true)
    val general = mockk<TextChannel>(relaxed = true)
    every { jda.awaitReady() } returns jda
    every { jda.getGuildChannelById("chat") } returns chat
    every { jda.getGuildChannelById("general") } returns general
    return jda
}
