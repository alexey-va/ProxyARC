package ru.arc.xserver.repos

import ru.arc.CommonCore
import ru.arc.xserver.ChannelListener
import ru.arc.xserver.RedisManager

class RedisRepoMessager(
    private val redisRepo: RedisRepo<*>,
    private val redisManager: RedisManager,
) : ChannelListener {
    override fun consume(channel: String, message: String, originServer: String) {
        if (originServer == CommonCore.config?.string("server-name", "proxy")) {
            return
        }
        redisRepo.receiveUpdate(message)
    }

    fun send(channel: String, message: String) {
        redisManager.publish(channel, message)
    }
}
