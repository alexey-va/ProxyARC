package ru.arc.join

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class JoinMessageCatalogPublicationTest : FreeSpec({
    "publisher flushes a changed snapshot and ignores an identical revision" {
        var current: JoinMessageCatalog? = null
        val persisted = mutableListOf<JoinMessageCatalog>()
        val publication = JoinMessageCatalogPublication(
            current = { current },
            persist = { snapshot ->
                persisted += snapshot
                current = snapshot
            },
        )
        val snapshot = JoinMessageCatalog(revision = "abc")

        publication.publish(snapshot) shouldBe true
        publication.publish(snapshot.copyForTest(updatedAt = 999)) shouldBe false
        persisted shouldBe listOf(snapshot)
    }

    "publisher sends prefix fields with the catalog" {
        var current: JoinMessageCatalog? = null
        val persisted = mutableListOf<JoinMessageCatalog>()
        val publication = JoinMessageCatalogPublication(
            current = { current },
            persist = { snapshot -> persisted += snapshot; current = snapshot },
        )
        val snapshot = JoinMessageCatalog(
            revision = "prefixes",
            joinPrefix = "<dark_green>● ",
            leavePrefix = "<dark_red>● ",
        )

        publication.publish(snapshot) shouldBe true
        persisted.single().joinPrefix shouldBe "<dark_green>● "
        persisted.single().leavePrefix shouldBe "<dark_red>● "
    }
})

private fun JoinMessageCatalog.copyForTest(updatedAt: Long): JoinMessageCatalog =
    JoinMessageCatalog(
        catalogId = catalogId,
        schemaVersion = schemaVersion,
        revision = revision,
        updatedAt = updatedAt,
        joinPrefix = joinPrefix,
        leavePrefix = leavePrefix,
        join = join,
        leave = leave,
    )
