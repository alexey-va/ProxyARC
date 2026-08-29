package ru.arc.core.modules

import ru.arc.FirstJoinData
import ru.arc.activity.PlayerActivityTracker
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity
import java.util.concurrent.TimeUnit

// ==================== Priority 50-59: Persistence ====================

object FirstJoinModule : PluginModule {
    override val name = "FirstJoin"
    override val priority = 50

    override fun init() {
        val data = FirstJoinData()
        data.load()
        Velocity.firstJoinData = data
    }

    override fun reload() {}

    override fun shutdown() {
        val data = Velocity.firstJoinData
        Velocity.firstJoinData = null
        data?.closeAsync()?.get(5, TimeUnit.SECONDS)
    }
}

object PlayerActivityModule : PluginModule {
    override val name = "PlayerActivity"
    override val priority = 56

    override fun init() {
        val redis = Velocity.redisManager ?: return
        val server = Velocity.requireProxyServer()
        val logger = Velocity.requireLogger()
        val tracker = PlayerActivityTracker(redis, logger)
        Velocity.playerActivityTracker = tracker
        tracker.start(server.allPlayers.map { it.uniqueId })
    }

    override fun reload() {}

    override fun shutdown() {
        val tracker = Velocity.playerActivityTracker
        val playerIds = Velocity.proxyServer?.allPlayers?.map { it.uniqueId }.orEmpty()
        if (tracker != null) {
            runCatching {
                tracker.markSeenAll(playerIds).get(5, TimeUnit.SECONDS)
            }.onFailure {
                Velocity.logger?.warn("Could not flush network player activity during shutdown", it)
            }
        }
        Velocity.playerActivityTracker = null
    }
}
