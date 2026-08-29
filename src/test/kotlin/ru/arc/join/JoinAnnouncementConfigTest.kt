package ru.arc.join

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files

class JoinAnnouncementConfigTest : FreeSpec({
    "canonical config maps join and leave to the correct message families" {
        val config =
            config(
                """
                messages:
                  join-prefix: "<dark_green>● "
                  leave-prefix: "<dark_red>● "
                  first-time: "<gray>Игрок <green>%player_name% <gray>впервые на сервере!"
                  join: "<gray>Игрок <green>%player_name% <gray>присоединился!"
                  leave: "<gray>Игрок <red>%player_name% <gray>вышел!"
                """.trimIndent(),
            )
        val messages = JoinAnnouncementConfig(config)

        messages.minecraftMessage(PublishedAnnouncement("Alex", JoinAnnouncementKind.JOIN, null)) shouldContain
            "присоединился"
        messages.minecraftMessage(PublishedAnnouncement("Alex", JoinAnnouncementKind.LEAVE, null)) shouldContain
            "вышел"
    }

    "legacy first-join key remains readable without adding a second marker" {
        val config =
            config(
                """
                messages:
                  first-join: "<blue>● <gray>Игрок <green>%player_name% <gray>зашёл впервые!"
                """.trimIndent(),
            )

        val rendered =
            JoinAnnouncementConfig(config).minecraftMessage(
                PublishedAnnouncement("Alex", JoinAnnouncementKind.FIRST_TIME, null),
            )

        rendered shouldContain "<blue>● <gray>Игрок <green>Alex"
        rendered shouldNotContain "❖"
    }

    "selected custom phrase receives the configured family prefix" {
        val config =
            config(
                """
                messages:
                  join-prefix: "<dark_green>● "
                """.trimIndent(),
            )

        JoinAnnouncementConfig(config).minecraftMessage(
            PublishedAnnouncement("Alex", JoinAnnouncementKind.JOIN, "%player_name% открыл калитку"),
        ) shouldContain "<dark_green>● Alex открыл калитку"
    }

    "bundled join-messages module provides the three correctly routed defaults" {
        val directory = Files.createTempDirectory("proxyarc-bundled-join-messages-")
        Config.copyDefaultConfig(
            ConfigManager.bundledModuleResource("join-messages.yml"),
            directory,
            replace = false,
        )
        val bundled = ConfigManager.ofModule(directory, "join-messages.yml")
        bundled.exists("messages.first-time") shouldBe true
        bundled.exists("messages.join") shouldBe true
        bundled.exists("messages.leave") shouldBe true
        val messages = JoinAnnouncementConfig(bundled)

        messages.minecraftMessage(PublishedAnnouncement("Alex", JoinAnnouncementKind.FIRST_TIME, null)) shouldContain
            "впервые"
        messages.minecraftMessage(PublishedAnnouncement("Alex", JoinAnnouncementKind.JOIN, null)) shouldContain
            "присоединился"
        messages.minecraftMessage(PublishedAnnouncement("Alex", JoinAnnouncementKind.LEAVE, null)) shouldContain
            "вышел"
    }

    "legacy phrases migrate once into the separate join-messages module" {
        ConfigManager.clear()
        val directory = Files.createTempDirectory("proxyarc-legacy-join-messages-")
        Files.writeString(
            directory.resolve("join_config.yml"),
            """
            messages:
              first-join: "<blue>● %player_name% впервые из старого файла"
              join: "<green>● %player_name% вошёл из старого файла"
              leave: "<red>● %player_name% вышел из старого файла"
            """.trimIndent(),
        )

        val messages = JoinAnnouncementConfig.load(directory)

        messages.minecraftMessage(PublishedAnnouncement("Alex", JoinAnnouncementKind.FIRST_TIME, null)) shouldContain
            "впервые из старого файла"
        messages.minecraftMessage(PublishedAnnouncement("Alex", JoinAnnouncementKind.JOIN, null)) shouldContain
            "вошёл из старого файла"
        Files.exists(directory.resolve("modules/join-messages.yml")) shouldBe true
    }
})

private fun config(contents: String): Config {
    val directory = Files.createTempDirectory("proxyarc-join-config-")
    Files.writeString(directory.resolve("join_config.yml"), contents)
    return Config(directory, "join_config.yml")
}
