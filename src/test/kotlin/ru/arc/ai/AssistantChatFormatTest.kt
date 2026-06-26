package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class AssistantChatFormatTest : FreeSpec({
    "AssistantChatFormat" - {
        "should match CMI global shout format" {
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  display-name: "Скорен"
                  shout-prefix: "&6Ⓖ &7"
                  message-format: "%shout%%suffix% &7%name% &8» &f%message%"
                """.trimIndent(),
            )
            val config = Config(dir, "assistant.yml")
            val redstone = AssistantChatFormat.DEFAULT_SUFFIX

            AssistantChatFormat.inGameMessage(config, "привет") shouldBe
                "&6Ⓖ &7$redstone &7Скорен &8» &fпривет"
        }

        "should accept CMI placeholder names" {
            val format = "{shout}%luckperms_suffix% &7{displayName} &8» &f{message}"
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  display-name: "Скорен"
                  shout-prefix: "&6Ⓖ &7"
                """.trimIndent(),
            )
            val config = Config(dir, "assistant.yml")
            val redstone = AssistantChatFormat.DEFAULT_SUFFIX

            AssistantChatFormat.applyPlaceholders(format, config, "test") shouldBe
                "&6Ⓖ &7$redstone &7Скорен &8» &ftest"
        }

        "should keep only first block and clamp length" {
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  max-message-length: 20
                """.trimIndent(),
            )
            val config = Config(dir, "assistant.yml")

            AssistantChatFormat.normalizeReply(
                config,
                "первая строка\n\nвторая строка которую надо выкинуть",
            ) shouldBe "первая строка"

            AssistantChatFormat.normalizeReply(
                config,
                "очень длинное сообщение которое точно не влезет в чат",
            ) shouldBe "очень длинное"
        }

        "should explain skip reason for пропускаю" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result = AssistantChatFormat.normalizeReplyDetail(config, "пропускаю")
            result.hasText shouldBe false
            result.skipReason shouldBe "model said пропускаю"
        }

        "should format discord like regular player chat" {
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("config.yml"),
                "discord:\n  chat-pattern: \"**%player_name%** » %message%\"\n",
            )
            val main = Config(dir, "config.yml")

            AssistantChatFormat.discordMessage(main, "Скорен", "привет") shouldBe
                "**Скорен** » привет"
        }
    }
})
