package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.config.ConfigManager
import ru.arc.config.ProxyConfigs
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class DiscordLinkProtectionServiceTest : FreeSpec({
    afterEach { ConfigManager.clear() }

    "recovery fails closed when the old Discord cannot receive its cancel button" {
        val root = Files.createTempDirectory("discord-link-protection")
        ProxyConfigs.module(root, "discord-integration.yml").also { config ->
            config.setBoolean("enabled", true)
            config.setString("guild-id", "1073279640912789595")
            config.setLong("recovery.link-protection-delay-seconds", 60)
            config.saveStrict()
        }
        ConfigManager.clear()
        val config = DiscordIntegrationConfig.load(root).also(DiscordIntegrationConfig::validate)
        val store = DiscordIntegrationStore(root.resolve("data/discord-integration.json"))
        val notifications = mockk<DiscordSecurityNotifier>(relaxed = true)
        every { notifications.notifySecurityAction(any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(false)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val service = DiscordLinkProtectionService(config, notifications, store, scheduler)
        val prepared =
            DiscordChallengeCompletionResult.RecoveryPrepared(
                challengeId = "challenge-id",
                currentLink =
                    DiscordIdentityLink(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "PlayerOne",
                        "1083092420394221699",
                        1,
                        1,
                    ),
                newDiscordUserId = "297430445975404544",
            )

        service.guard(prepared).join() shouldBe false
        verify(exactly = 1) { notifications.alert(match { it.contains("не доставлена") }) }

        service.close()
        scheduler.shutdownNow()
    }
})
