package ru.arc.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class ProxyOnlineSnapshotTest : FreeSpec({
    "empty and populated stable observations are complete" {
        for (names in listOf(emptyList(), listOf("GrocerMC", "CodexQA1"), listOf("PlayerOne"))) {
            val snapshot = ProxyOnlineSnapshot.sample({ names }, { names.size })
            snapshot.complete shouldBe true
            snapshot.names shouldBe names.sorted()
        }
    }
    "changing roster is incomplete even when the count stays equal" {
        var reads = 0
        ProxyOnlineSnapshot.sample({ if (reads++ == 0) listOf("CodexQA1") else listOf("PlayerOne") }, { 1 }).complete shouldBe false
    }
    "truncated or inconsistent counts and duplicate identities are incomplete" {
        ProxyOnlineSnapshot.sample({ emptyList() }, { 1 }).complete shouldBe false
        ProxyOnlineSnapshot.sample({ listOf("PlayerOne", "playerone") }, { 2 }).complete shouldBe false
        ProxyOnlineSnapshot.sample({ listOf("bad name") }, { 1 }).complete shouldBe false
        ProxyOnlineSnapshot.sample({ (0..4096).map { "P$it" } }, { 4097 }).complete shouldBe false
    }
    "slow and backwards observations are incomplete" {
        for (end in listOf(Instant.ofEpochSecond(90), Instant.ofEpochSecond(103))) {
            var reads = 0
            ProxyOnlineSnapshot.sample({ emptyList() }, { 0 }, { if (reads++ == 0) Instant.ofEpochSecond(100) else end }).complete shouldBe false
        }
    }
    "unavailable proxy never reports trustworthy emptiness" {
        val snapshot = ProxyOnlineSnapshot.capture(null)
        snapshot.available shouldBe false
        snapshot.complete shouldBe false
    }
})
