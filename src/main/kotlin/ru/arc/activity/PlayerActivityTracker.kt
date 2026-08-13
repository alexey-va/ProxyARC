package ru.arc.activity

import org.slf4j.Logger
import ru.arc.redis.RedisOperations
import ru.arc.redis.activity.PlayerActivityStore
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Velocity-owned writer for network player activity evidence. */
class PlayerActivityTracker(
    redis: RedisOperations,
    private val logger: Logger,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val store = PlayerActivityStore(redis, clockMillis)
    private val coverageLock = Any()

    @Volatile
    private var coverageReady = false

    private var coverageInFlight: CompletableFuture<Long>? = null

    fun start(onlinePlayers: Collection<UUID>): CompletableFuture<*> = markSeenAll(onlinePlayers)

    fun markSeen(playerId: UUID): CompletableFuture<*> =
        ensureCoverageStarted()
            .thenCompose { store.markSeen(playerId, clockMillis()) }
            .whenComplete { _, failure ->
                if (failure != null) {
                    logger.warn("Could not persist network player activity", failure)
                }
            }

    fun markSeenAll(playerIds: Collection<UUID>): CompletableFuture<*> {
        val timestamp = clockMillis()
        return ensureCoverageStarted().thenCompose {
            val uniquePlayerIds = playerIds.distinct()
            if (uniquePlayerIds.isEmpty()) {
                CompletableFuture.completedFuture(null)
            } else {
                CompletableFuture.allOf(
                    *uniquePlayerIds.map { playerId ->
                        store.markSeen(playerId, timestamp)
                    }.toTypedArray(),
                )
            }
        }.whenComplete { _, failure ->
            if (failure != null) {
                logger.warn("Could not persist network player activity heartbeat", failure)
            }
        }
    }

    private fun ensureCoverageStarted(): CompletableFuture<Long> =
        synchronized(coverageLock) {
            if (coverageReady) return@synchronized CompletableFuture.completedFuture(clockMillis())
            coverageInFlight?.let { return@synchronized it }
            store.ensureCoverageStartedAt().also { future ->
                coverageInFlight = future
                future.whenComplete { _, failure ->
                    synchronized(coverageLock) {
                        if (failure == null) coverageReady = true
                        if (coverageInFlight === future) coverageInFlight = null
                    }
                }
            }
        }
}
