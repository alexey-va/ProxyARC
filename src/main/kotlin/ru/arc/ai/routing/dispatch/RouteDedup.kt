package ru.arc.ai.routing.dispatch

import java.util.concurrent.ConcurrentHashMap

object RouteDedup {
    private val recentKeys = ConcurrentHashMap<String, Long>()
    private const val DEDUP_MS = 30 * 60 * 1000L

    fun isDuplicate(key: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = recentKeys[key]
        if (previous != null && now - previous < DEDUP_MS) {
            return true
        }
        recentKeys[key] = now
        pruneOld(now)
        return false
    }

    fun clear() {
        recentKeys.clear()
    }

    private fun pruneOld(now: Long) {
        if (recentKeys.size < 200) return
        recentKeys.entries.removeIf { now - it.value >= DEDUP_MS }
    }
}
