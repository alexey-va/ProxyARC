package ru.arc.core.modules

import ru.arc.config.ProxyConfigs
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity
import ru.arc.xserver.JoinMessages
import ru.arc.xserver.PlayerListAnnouncer
import ru.arc.xserver.repos.RedisRepo

// ==================== Priority 60-69: Cross-Server ====================

object PlayerListModule : PluginModule {
    override val name = "PlayerList"
    override val priority = 60

    override fun init() {
        val redis = Velocity.redisManager ?: return
        Velocity.playerListAnnouncer =
            PlayerListAnnouncer(
                ProxyConfigs.main(),
                redis,
                "arc.proxy_player_list",
            )
    }

    override fun shutdown() {
        Velocity.playerListAnnouncer = null
    }
}

object JoinMessagesModule : PluginModule {
    override val name = "JoinMessages"
    override val priority = 65

    override fun init() {
        val redis = Velocity.redisManager ?: return
        if (Velocity.joinMessagesRedisRepo != null) return
        Velocity.joinMessagesRedisRepo =
            RedisRepo.builder(JoinMessages::class.java)
                .id("join_messages")
                .loadAll(true)
                .updateChannel("arc.join_messages_update")
                .redisManager(redis)
                .storageKey("arc.join_messages")
                .saveInterval(200L)
                .build()
    }

    override fun shutdown() {
        Velocity.joinMessagesRedisRepo?.cancelTasks()
        Velocity.joinMessagesRedisRepo?.close()
        Velocity.joinMessagesRedisRepo = null
    }
}
