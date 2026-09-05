package ru.arc.core.modules

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import ru.arc.Common
import ru.arc.config.ConfigManager
import ru.arc.join.JoinAnnouncementKind
import ru.arc.join.JoinMessageCatalog
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.xserver.JoinMessages
import java.nio.file.Files

class JoinMessageCatalogModuleTest : FreeSpec({
    afterTest {
        JoinMessageCatalogModule.shutdown()
        ConfigManager.clear()
    }

    "publishes a durable catalog snapshot and only resolves phrases from that catalog" {
        val redis = InMemoryRedis(ServerIdentity { "proxy-test" })
        val dataRoot = Files.createTempDirectory("proxyarc-catalog-module-")

        JoinMessageCatalogModule.start(redis, dataRoot)

        val raw = redis.getHash(JoinMessageCatalogModule.STORAGE_KEY)[JoinMessageCatalog.CATALOG_ID]
        val catalog = Common.gson.fromJson(raw, JoinMessageCatalog::class.java)
        catalog.join.size shouldBe 59
        catalog.leave.size shouldBe 58
        redis.listenerCount(JoinMessageCatalogModule.UPDATE_CHANNEL) shouldBe 1

        val selected = JoinMessages("Alex").apply { joinMessages = setOf(catalog.join.first().message) }
        JoinMessageCatalogModule.selectedMessage(selected, JoinAnnouncementKind.JOIN) shouldBe catalog.join.first().message
        selected.joinMessages = setOf("<red>not in catalog")
        JoinMessageCatalogModule.selectedMessage(selected, JoinAnnouncementKind.JOIN) shouldBe null

        JoinMessageCatalogModule.shutdown()
        redis.listenerCount(JoinMessageCatalogModule.UPDATE_CHANNEL) shouldBe 0
    }

    "resolves valid custom suffixes, ignores invalid or deleted selections, and never uses them for first join" {
        val redis = InMemoryRedis(ServerIdentity { "proxy-test" })
        val dataRoot = Files.createTempDirectory("proxyarc-custom-catalog-module-")
        JoinMessageCatalogModule.start(redis, dataRoot)
        val catalog = Common.gson.fromJson(
            redis.getHash(JoinMessageCatalogModule.STORAGE_KEY)[JoinMessageCatalog.CATALOG_ID],
            JoinMessageCatalog::class.java,
        )

        val selected = JoinMessages("Alex").apply {
            joinMessages = setOf("deleted", "%player_name% welcome", catalog.join.first().message)
            leaveMessages = setOf("%player_name% bye")
            customJoinMessages = setOf("  welcome  ", "<red>bad")
            customLeaveMessages = setOf("bye")
        }
        setOf("%player_name% welcome", catalog.join.first().message) shouldContain
            JoinMessageCatalogModule.selectedMessage(selected, JoinAnnouncementKind.JOIN)!!
        selected.joinMessages = setOf("deleted")
        JoinMessageCatalogModule.selectedMessage(selected, JoinAnnouncementKind.JOIN) shouldBe null
        JoinMessageCatalogModule.selectedMessage(selected, JoinAnnouncementKind.LEAVE) shouldBe "%player_name% bye"
        JoinMessageCatalogModule.selectedMessage(selected, JoinAnnouncementKind.FIRST_TIME) shouldBe null
    }

    "invalid startup config releases the Redis subscription" {
        val redis = InMemoryRedis(ServerIdentity { "proxy-test" })
        val dataRoot = Files.createTempDirectory("proxyarc-invalid-catalog-module-")
        val modules = Files.createDirectories(dataRoot.resolve("modules"))
        Files.writeString(
            modules.resolve("join-messages.yml"),
            """
            messages:
              join: "joined"
              leave: "left"
            catalog:
              join:
                duplicate:
                  message: "one"
                  material: PAPER
              leave:
                duplicate:
                  message: "two"
                  material: BARRIER
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            JoinMessageCatalogModule.start(redis, dataRoot)
        }

        redis.listenerCount(JoinMessageCatalogModule.UPDATE_CHANNEL) shouldBe 0
    }
})
