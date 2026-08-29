package ru.arc.join

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import ru.arc.config.ConfigManager
import java.nio.file.Files

class JoinMessageCatalogConfigTest : FreeSpec({
    "bundled catalog is complete, stable, and gives every phrase its own icon" {
        ConfigManager.clear()
        val directory = Files.createTempDirectory("proxyarc-join-catalog-")

        val first = JoinMessageCatalogConfig.load(directory).snapshot(updatedAt = 100)
        val second = JoinMessageCatalogConfig.load(directory).snapshot(updatedAt = 200)

        first.join shouldHaveSize 59
        first.leave shouldHaveSize 58
        first.join.map(JoinMessageCatalogEntry::id).distinct() shouldHaveSize first.join.size
        first.leave.map(JoinMessageCatalogEntry::id).distinct() shouldHaveSize first.leave.size
        first.join.map(JoinMessageCatalogEntry::material).distinct() shouldHaveSize first.join.size
        first.leave.map(JoinMessageCatalogEntry::material).distinct() shouldHaveSize first.leave.size
        first.join.forEach { it.displayName.shouldStartWith("<italic:false>") }
        first.leave.forEach { it.displayName.shouldStartWith("<italic:false>") }
        first.revision shouldBe second.revision
        first.updatedAt shouldBe 100
        second.updatedAt shouldBe 200
    }

    "invalid catalog is rejected before it can replace the Redis snapshot" {
        val config = testCatalogConfig(
            """
            catalog:
              join:
                duplicate:
                  order: 1
                  message: "first"
                  material: PAPER
                  display-name: "<italic:false>First"
              leave:
                duplicate:
                  order: 1
                  message: "second"
                  material: BARRIER
                  display-name: "<italic:false>Second"
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            JoinMessageCatalogConfig(config).snapshot(updatedAt = 1)
        }
    }

    "catalog startup preserves legacy announcement messages before creating the shared file" {
        ConfigManager.clear()
        val directory = Files.createTempDirectory("proxyarc-catalog-legacy-announcements-")
        Files.writeString(
            directory.resolve("join_config.yml"),
            """
            messages:
              join: "<green>● %player_name% вошёл со старым текстом"
              leave: "<red>● %player_name% вышел со старым текстом"
            """.trimIndent(),
        )

        JoinMessageCatalogConfig.load(directory).snapshot(updatedAt = 1)
        val announcements = JoinAnnouncementConfig.load(directory)

        announcements.minecraftMessage(
            PublishedAnnouncement("Alex", JoinAnnouncementKind.JOIN, null),
        ) shouldContain "вошёл со старым текстом"
    }
})

private fun testCatalogConfig(contents: String): ru.arc.config.Config {
    val directory = Files.createTempDirectory("proxyarc-join-catalog-invalid-")
    Files.writeString(directory.resolve("catalog.yml"), contents)
    return ru.arc.config.Config(directory, "catalog.yml")
}
