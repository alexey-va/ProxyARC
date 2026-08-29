package ru.arc.telegram

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import ru.arc.config.ProxyConfigs
import java.nio.file.Files

class TelegramConfigTest : FreeSpec({
    afterEach { ConfigManager.clear() }

    "loads explicit bridge destinations and information mirror" {
        val root = Files.createTempDirectory("telegram-config-")
        ProxyConfigs.module(root, "telegram.yml").also { config ->
            config.setLong("bridge.chat.chat-id", -1_001L)
            config.setInt("bridge.chat.thread-id", 7)
            config.setLong("bridge.general.chat-id", -1_001L)
            config.setInt("bridge.general.thread-id", 8)
            config.setLong("channels.information.chat-id", -2_002L)
            config.saveStrict()
        }
        ConfigManager.clear()

        val config = TelegramConfig.load(root)

        config.chatDestination shouldBe TelegramDestination("-1001", 7)
        config.generalDestinations().shouldContainExactly(
            TelegramDestination("-1001", 8),
            TelegramDestination("-2002"),
        )
    }

    "loads the public Telegram community URL from the information channel config" {
        val root = Files.createTempDirectory("telegram-community-url-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/telegram.yml"),
            """
            channels:
              information:
                url: "https://t.me/ruscrafting"
            """.trimIndent(),
        )

        val config = TelegramConfig.load(root)

        config.informationUrl shouldBe "https://t.me/ruscrafting"
    }

    "loads the one-pixel negative space after the Telegram chat icon" {
        val root = Files.createTempDirectory("telegram-chat-spacing-")

        val config = TelegramConfig.load(root)

        config.chatFormat shouldBe
            "<white></white>󰼑 <dark_gray>| <gray>%sender% <dark_gray>» <white>%message%"
    }

    "legacy chat id and topics remain valid fallbacks" {
        val root = Files.createTempDirectory("telegram-legacy-config-")
        ProxyConfigs.module(root, "telegram.yml").also { config ->
            config.setLong("chat-id", -3_003L)
            config.setInt("bridge.chat.chat-id", 0)
            config.setInt("bridge.general.chat-id", 0)
            config.setInt("topics.chat", 11)
            config.setInt("topics.general", 12)
            config.saveStrict()
        }
        ConfigManager.clear()

        val config = TelegramConfig.load(root)

        config.chatDestination shouldBe TelegramDestination("-3003", 11)
        config.generalDestination shouldBe TelegramDestination("-3003", 12)
    }

    "duplicate general and information targets are sent once" {
        val config =
            TestTelegramConfig(
                generalDestination = TelegramDestination("-1001", 5),
                informationDestination = TelegramDestination("-1001", 5),
            )

        config.generalDestinations().shouldContainExactly(TelegramDestination("-1001", 5))
    }

    "modular Telegram config keeps the token in the legacy credential file" {
        val root = Files.createTempDirectory("telegram-credential-fallback-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/telegram.yml"),
            """
            enabled: true
            token: none
            identity:
              enabled: false
            """.trimIndent(),
        )
        Files.writeString(root.resolve("telegram.yml"), "token: test-runtime-token")

        val config = TelegramConfig.load(root)

        config.enabled shouldBe true
        config.token shouldBe "test-runtime-token"
    }

    "chat ids are canonical signed integers" {
        TelegramChatIds.isValid("-100123") shouldBe true
        TelegramChatIds.isValid("123") shouldBe true
        TelegramChatIds.isValid("0") shouldBe false
        TelegramChatIds.isValid("-001") shouldBe false
        TelegramChatIds.isValid("@channel") shouldBe false
    }

    "loads bounded Telegram identity policy" {
        val root = Files.createTempDirectory("telegram-identity-config-")
        Files.writeString(
            root.resolve("telegram.yml"),
            """
            identity:
              enabled: true
              private-chat-only: true
              allowed-backends: ["Survival", "spawn"]
              codes:
                length: 99
                ttl-seconds: 5
                max-attempts-per-window: 500
            """.trimIndent(),
        )
        val config = TelegramConfig(ConfigManager.of(root, "telegram.yml"))

        config.identityEnabled shouldBe true
        config.identityPrivateChatOnly shouldBe true
        config.identityAllowedBackends.shouldContainExactly("survival", "spawn")
        config.identityCodeLength shouldBe 16
        config.identityCodeTtlSeconds shouldBe 60
        config.identityMaxAttemptsPerWindow shouldBe 50
    }
})
