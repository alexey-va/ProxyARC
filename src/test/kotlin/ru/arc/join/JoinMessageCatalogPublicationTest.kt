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
})

private fun JoinMessageCatalog.copyForTest(updatedAt: Long): JoinMessageCatalog =
    JoinMessageCatalog(
        catalogId = catalogId,
        schemaVersion = schemaVersion,
        revision = revision,
        updatedAt = updatedAt,
        join = join,
        leave = leave,
    )
