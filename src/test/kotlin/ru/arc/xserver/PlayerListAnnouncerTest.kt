package ru.arc.xserver

import com.google.gson.reflect.TypeToken
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import java.util.UUID

class PlayerListAnnouncerTest : FreeSpec({
    "update refreshes both username and server for an existing uuid" {
        val redis = InMemoryRedis()
        val announcer = PlayerListAnnouncer(redis, "players")
        val uuid = UUID.randomUUID()
        announcer.addPlayer(uuid, "OldName", "lobby")

        announcer.updatePlayer(uuid, "NewName", "survival")

        announcer.serverForUsername("OldName") shouldBe null
        announcer.serverForUsername("newname") shouldBe "survival"
    }

    "announce publishes a stable snapshot" {
        val redis = InMemoryRedis()
        val announcer = PlayerListAnnouncer(redis, "players")
        val uuid = UUID.randomUUID()
        announcer.addPlayer(uuid, "Alex", "survival")

        announcer.announce()

        val payload = redis.getPublishedMessages().single().message
        val type = object : TypeToken<List<PlayerListAnnouncer.PlayerData>>() {}.type
        val players: List<PlayerListAnnouncer.PlayerData> = com.google.gson.Gson().fromJson(payload, type)
        players.single().username shouldBe "Alex"
        players.single().server shouldBe "survival"
    }
})
