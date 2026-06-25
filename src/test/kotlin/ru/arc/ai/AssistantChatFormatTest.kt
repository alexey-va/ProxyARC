package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class AssistantChatFormatTest : FreeSpec({
    "AssistantChatFormat" - {
        "should format in-game message like players with bot icon" {
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  display-name: "скорен"
                  message-format: "<yellow>◇ <gray>%name% <dark_gray>» <white>%message%"
                """.trimIndent(),
            )
            val config = Config(dir, "assistant.yml")

            AssistantChatFormat.inGameMessage(config, "привет") shouldBe
                "<yellow>◇ <gray>скорен <dark_gray>» <white>привет"
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
