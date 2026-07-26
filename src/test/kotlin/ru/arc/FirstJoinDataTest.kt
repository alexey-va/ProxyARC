package ru.arc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class FirstJoinDataTest : FreeSpec({
    "first join data survives an atomic save and reload" {
        val directory = Files.createTempDirectory("proxyarc-first-join-")
        val path = directory.resolve("first_time_join.json")
        val data = FirstJoinData(path)

        data.firstTimeJoin("Steve") shouldBe true
        data.markAsJoined("Steve")
        val joinedAt = data.getFirstJoinTime("Steve")
        data.save()

        val reloaded = FirstJoinData(path)
        reloaded.load()
        reloaded.firstTimeJoin("Steve") shouldBe false
        reloaded.getFirstJoinTime("Steve") shouldBe joinedAt
    }

    "loading a missing file starts with an empty data set" {
        val path = Files.createTempDirectory("proxyarc-first-join-empty-").resolve("missing.json")
        val data = FirstJoinData(path)

        data.load()

        data.firstTimeJoin("Alex") shouldBe true
    }

    "player names are matched case-insensitively" {
        val path = Files.createTempDirectory("proxyarc-first-join-case-").resolve("first_time_join.json")
        val data = FirstJoinData(path)
        data.markAsJoined("Steve")

        data.firstTimeJoin("steve") shouldBe false
        data.getFirstJoinTime("STEVE") shouldBe data.getFirstJoinTime("Steve")
    }
})
