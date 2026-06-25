package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class AssistantChatFormatTest : FreeSpec({
    "AssistantChatFormat" - {
        "should match CMI GeneralFormat with bot suffix" {
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  display-name: "скорен"
                  suffix: "&e◇"
                  message-format: "%suffix% &7%name% &8» &f%message%"
                """.trimIndent(),
            )
            val config = Config(dir, "assistant.yml")

            AssistantChatFormat.inGameMessage(config, "привет") shouldBe
                "&e◇ &7скорен &8» &fпривет"
        }

        "should accept CMI placeholder names" {
            val format = "%luckperms_suffix% &7{displayName} &8» &f{message}"
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  display-name: "скорен"
                  suffix: "&e◇"
                """.trimIndent(),
            )
            val config = Config(dir, "assistant.yml")

            AssistantChatFormat.applyPlaceholders(format, config, "test") shouldBe
                "&e◇ &7скорен &8» &ftest"
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
