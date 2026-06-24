package ru.arc.xserver

import com.google.gson.Gson
import ru.arc.config.Config
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class PlayerListAnnouncer(
    private val config: Config,
    private val redisManager: RedisManager,
    private val channel: String,
) {
    private val gson = Gson()
    private val map: MutableMap<UUID, PlayerData> = ConcurrentHashMap()

    fun addPlayer(uuid: UUID, username: String, server: String) {
        map[uuid] = PlayerData(username, server, uuid, System.currentTimeMillis())
    }

    fun updatePlayer(uuid: UUID, username: String, server: String) {
        val data = map[uuid]
        if (data != null) {
            data.server = server
        } else {
            addPlayer(uuid, username, server)
        }
    }

    fun removePlayer(uuid: UUID) {
        map.remove(uuid)
    }

    fun removeAllPlayers() {
        map.clear()
    }

    fun announce() {
        CompletableFuture.supplyAsync { gson.toJson(map.values) }
            .thenAccept { json -> redisManager.publish(channel, json) }
    }

    data class PlayerData(
        var username: String,
        var server: String,
        val uuid: UUID,
        val joinTime: Long,
    )
}
