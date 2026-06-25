package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class AssistantChatFormatTest : FreeSpec({
    "AssistantChatFormat" - {
        "should match CMI global shout with gold bot name" {
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  display-name: "скорен"
                  shout-prefix: "&6Ⓖ &7"
                  suffix: ""
                  name-color: "&e"
                  message-format: "%shout%%suffix%%name_color%%name% &8» &f%message%"
                """.trimIndent(),
            )
            val config = Config(dir, "assistant.yml")

            AssistantChatFormat.inGameMessage(config, "привет") shouldBe
                "&6Ⓖ &7&eскорен &8» &fпривет"
        }

        "should accept CMI placeholder names" {
            val format = "{shout}%luckperms_suffix% &7{displayName} &8» &f{message}"
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  display-name: "скорен"
                  shout-prefix: "&6Ⓖ &7"
                  suffix: ""
                """.trimIndent(),
            )
            val config = Config(dir, "assistant.yml")

            AssistantChatFormat.applyPlaceholders(format, config, "test") shouldBe
                "&6Ⓖ &7 &7скорен &8» &ftest"
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

        "should format discord like regular player chat" {
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("config.yml"),
                "discord:\n  chat-pattern: \"**%player_name%** » %message%\"\n",
            )
            val main = Config(dir, "config.yml")

            AssistantChatFormat.discordMessage(main, "скорен", "привет") shouldBe
                "**скорен** » привет"
        }
    }
})
