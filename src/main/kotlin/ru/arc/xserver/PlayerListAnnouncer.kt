package ru.arc.xserver

import com.google.gson.Gson
import ru.arc.redis.RedisOperations
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerListAnnouncer(
    private val redisManager: RedisOperations,
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
            data.username = username
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
        val snapshot = map.values.map(PlayerData::copy)
        redisManager.publish(channel, gson.toJson(snapshot))
    }

    fun serverForUsername(username: String): String? =
        map.values.firstOrNull { it.username.equals(username, ignoreCase = true) }?.server

    data class PlayerData(
        var username: String,
        var server: String,
        val uuid: UUID,
        val joinTime: Long,
    )
}
