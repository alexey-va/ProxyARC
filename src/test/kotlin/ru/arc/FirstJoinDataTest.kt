package ru.arc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FirstJoinDataTest : FreeSpec({
    "claiming a first join persists it without waiting for a periodic save" {
        val directory = Files.createTempDirectory("proxyarc-first-join-")
        val path = directory.resolve("first_time_join.json")
        val data = FirstJoinData(path)

        val claim = data.claimFirstJoin("Steve", joinedAt = 42L)
        claim.firstTime shouldBe true
        claim.persisted.get(5, TimeUnit.SECONDS)

        val reloaded = FirstJoinData(path)
        reloaded.load()
        reloaded.joinedAt("Steve") shouldBe 42L

        data.closeAsync().get(5, TimeUnit.SECONDS)
        reloaded.closeAsync().get(5, TimeUnit.SECONDS)
    }

    "loading a missing file starts with an empty data set" {
        val path = Files.createTempDirectory("proxyarc-first-join-empty-").resolve("missing.json")
        val data = FirstJoinData(path)

        data.load()

        data.joinedAt("Alex") shouldBe null
        data.closeAsync().get(5, TimeUnit.SECONDS)
    }

    "reading an unknown first-join timestamp does not claim the first join" {
        val path = Files.createTempDirectory("proxyarc-first-join-read-").resolve("first_time_join.json")
        val data = FirstJoinData(path)

        data.joinedAt("Alex") shouldBe null
        data.joinedAt("Alex")

        data.joinedAt("Alex") shouldBe null
        data.closeAsync().get(5, TimeUnit.SECONDS)
    }

    "player names are matched case-insensitively" {
        val path = Files.createTempDirectory("proxyarc-first-join-case-").resolve("first_time_join.json")
        val data = FirstJoinData(path)
        data.claimFirstJoin("Steve", joinedAt = 84L).persisted.get(5, TimeUnit.SECONDS)

        data.joinedAt("steve") shouldBe 84L
        data.joinedAt("STEVE") shouldBe data.joinedAt("Steve")
        data.closeAsync().get(5, TimeUnit.SECONDS)
    }

    "concurrent claims elect exactly one first join" {
        val path = Files.createTempDirectory("proxyarc-first-join-race-").resolve("first_time_join.json")
        val data = FirstJoinData(path)
        val executor = Executors.newFixedThreadPool(8)

        val claims =
            try {
                executor.invokeAll(
                    List(32) {
                        java.util.concurrent.Callable {
                            data.claimFirstJoin("Alex", joinedAt = 126L)
                        }
                    },
                ).map { it.get(5, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        claims.count { it.firstTime } shouldBe 1
        data.joinedAt("alex") shouldBe 126L
        data.closeAsync().get(5, TimeUnit.SECONDS)
    }
})
