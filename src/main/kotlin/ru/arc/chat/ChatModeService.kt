package ru.arc.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ru.arc.Common
import ru.arc.redis.RedisOperations
import ru.arc.repository.CachedRepository
import ru.arc.repository.redisRepo
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

object ChatModeService {
    @Volatile
    private var repository: CachedRepository<PlayerChatMode>? = null

    @Volatile
    private var chatModes: ChatModeRepository? = null

    @Volatile
    private var scope: CoroutineScope? = null

    @Synchronized
    fun start(redis: RedisOperations) {
        if (repository != null) return
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val newRepository =
                redisRepo<PlayerChatMode>(
                    redis = redis,
                    gson = Common.gson,
                    id = ChatModeRepository.REPOSITORY_ID,
                    storageKey = ChatModeRepository.STORAGE_KEY,
                    updateChannel = ChatModeRepository.UPDATE_CHANNEL,
                    scope = newScope,
                ) {
                    loadAllOnStart(true)
                    // Proxy chat routing is synchronous; retain the complete
                    // mode mirror to avoid a first-message race after join.
                    enableCleanup(false)
                    saveInterval(1.seconds)
                }
            repository = newRepository
            chatModes = ChatModeRepository(newRepository)
            scope = newScope
        } catch (failure: Throwable) {
            newScope.cancel()
            throw failure
        }
    }

    fun getModeNow(playerId: UUID): ChatMode =
        chatModes?.getModeNow(playerId) ?: ChatMode.LOCAL

    fun track(playerId: UUID) {
        chatModes?.track(playerId)
    }

    fun untrack(playerId: UUID) {
        chatModes?.untrack(playerId)
    }

    @Synchronized
    fun shutdown() {
        val currentRepository = repository
        repository = null
        chatModes = null
        scope = null
        if (currentRepository != null) {
            runBlocking { currentRepository.shutdown() }
        }
    }
}
