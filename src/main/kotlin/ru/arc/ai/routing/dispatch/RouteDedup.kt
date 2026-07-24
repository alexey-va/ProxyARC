package ru.arc.ai.routing.dispatch

import java.util.concurrent.ConcurrentHashMap

object RouteDedup {
    private val recentKeys = ConcurrentHashMap<String, Long>()
    private const val DEDUP_MS = 5_000L

    fun isDuplicate(
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(recentKeys) {
            val previous = recentKeys[key]
            if (previous != null && nowMs - previous < DEDUP_MS) {
                return true
            }
            recentKeys[key] = nowMs
            pruneOld(nowMs)
            return false
        }
    }

    fun clear() {
        recentKeys.clear()
    }

    private fun pruneOld(now: Long) {
        if (recentKeys.size < 200) return
        recentKeys.entries.removeIf { now - it.value >= DEDUP_MS }
    }
}
