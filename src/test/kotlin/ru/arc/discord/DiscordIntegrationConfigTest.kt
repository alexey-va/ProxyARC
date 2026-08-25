package ru.arc.discord

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import ru.arc.config.ProxyConfigs
import java.nio.file.Files

class DiscordIntegrationConfigTest : FreeSpec({
    afterEach { ConfigManager.clear() }

    "loads and validates a configured integration" {
        val root = Files.createTempDirectory("discord-integration-config")
        ProxyConfigs.module(root, "discord-integration.yml").also { config ->
            config.setBoolean("enabled", true)
            config.setString("guild-id", "1073279640912789595")
            config.setString("channels.status", "1085621965412388864")
            config.saveStrict()
        }
        ConfigManager.clear()

        val loaded = DiscordIntegrationConfig.load(root).also(DiscordIntegrationConfig::validate)
        loaded.enabled shouldBe true
        loaded.statusChannelId shouldBe "1085621965412388864"
        loaded.eventReminderMinutes shouldBe listOf(60, 15)
    }

    "rejects unsafe notification fanout" {
        val root = Files.createTempDirectory("discord-integration-rate")
        ProxyConfigs.module(root, "discord-integration.yml").also { config ->
            config.setBoolean("enabled", true)
            config.setString("guild-id", "1073279640912789595")
            config.setInt("notifications.max-per-minute", 500)
            config.saveStrict()
        }
        ConfigManager.clear()

        shouldThrow<IllegalArgumentException> {
            DiscordIntegrationConfig.load(root).validate()
        }
    }
})
