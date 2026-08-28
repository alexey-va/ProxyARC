package ru.arc.channelsync

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import ru.arc.telegram.TelegramDestination
import java.nio.file.Files

class ChannelSyncConfigTest : FreeSpec({
    "loads typed bidirectional channel mappings" {
        val root = Files.createTempDirectory("channel-sync-config-")
        Files.writeString(
            root.resolve("channel-sync.yml"),
            """
            enabled: true
            mappings:
              - id: community
                discord-channel-id: "1073279998359765042"
                telegram:
                  chat-id: "-1001234567890"
                  thread-id: 42
                  username: "ruscrafting_chat"
                direction: telegram-to-discord
                sync-edits: false
                sync-deletes: true
            """.trimIndent(),
        )

        val config = ChannelSyncConfig(ConfigManager.of(root, "channel-sync.yml"))

        config.enabled shouldBe true
        config.validatedMappings().shouldContainExactly(
            ChannelSyncMapping(
                id = "community",
                discordChannelId = "1073279998359765042",
                telegram = TelegramDestination("-1001234567890", 42),
                telegramUsername = "ruscrafting_chat",
                direction = ChannelSyncDirection.TELEGRAM_TO_DISCORD,
                syncEdits = false,
                syncDeletes = true,
            ),
        )
    }

    "rejects destinations that would create ambiguous fan-out" {
        val root = Files.createTempDirectory("channel-sync-duplicate-")
        Files.writeString(
            root.resolve("channel-sync.yml"),
            """
            mappings:
              - id: one
                discord-channel-id: "1073279998359765042"
                telegram: {chat-id: "-1001234567890", thread-id: 42}
              - id: two
                discord-channel-id: "1073279998359765043"
                telegram: {chat-id: "-1001234567890", thread-id: 42}
            """.trimIndent(),
        )
        val config = ChannelSyncConfig(ConfigManager.of(root, "channel-sync.yml"))

        shouldThrow<IllegalArgumentException> { config.validatedMappings() }
    }
})
