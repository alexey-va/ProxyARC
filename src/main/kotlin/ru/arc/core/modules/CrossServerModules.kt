package ru.arc.core.modules

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import ru.arc.Common
import ru.arc.core.PluginModule
import ru.arc.redis.RedisOperations
import ru.arc.repository.CachedRepository
import ru.arc.repository.redisRepo
import ru.arc.velocity.Velocity
import ru.arc.xserver.JoinMessages
import ru.arc.xserver.PlayerListAnnouncer
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

// ==================== Priority 60-69: Cross-Server ====================

object PlayerListModule : PluginModule {
    override val name = "PlayerList"
    override val priority = 60

    override fun init() {
        val redis = Velocity.redisManager ?: return
        Velocity.playerListAnnouncer =
            PlayerListAnnouncer(
                redis,
                "arc.proxy_player_list",
            )
    }

    override fun shutdown() {
        Velocity.playerListAnnouncer = null
    }

    override fun reload() {}
}

object JoinMessagesModule : PluginModule {
    override val name = "JoinMessages"
    override val priority = 65

    @Volatile
    private var repository: CachedRepository<JoinMessages>? = null

    @Volatile
    private var scope: CoroutineScope? = null

    override fun init() {
        val redis = Velocity.redisManager ?: return
        start(redis)
    }

    @Synchronized
    internal fun start(redis: RedisOperations) {
        if (repository != null) return
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            repository =
                redisRepo<JoinMessages>(
                    redis = redis,
                    gson = Common.gson,
                    id = "join_messages",
                    storageKey = "arc.join_messages",
                    updateChannel = "arc.join_messages_update",
                    scope = newScope,
                ) {
                    loadAllOnStart(true)
                    saveInterval(10.seconds)
                }
            scope = newScope
        } catch (e: Exception) {
            newScope.cancel()
            throw e
        }
    }

    internal fun loadAsync(playerName: String): CompletableFuture<JoinMessages?> {
        val currentRepository = repository ?: return CompletableFuture.completedFuture(null)
        val currentScope = scope ?: return CompletableFuture.completedFuture(null)
        return currentScope.future {
            currentRepository.get(playerName).getOrThrow()
        }
    }

    @Synchronized
    override fun shutdown() {
        val currentRepository = repository
        repository = null
        scope = null
        if (currentRepository != null) {
            runBlocking { currentRepository.shutdown() }
        }
    }

    override fun reload() {}
}
