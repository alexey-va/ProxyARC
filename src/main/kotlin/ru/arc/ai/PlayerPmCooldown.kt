package ru.arc.ai

import java.util.concurrent.ConcurrentHashMap

/** Prevents rapid-fire PM spam to the same player across tool chains. */
object PlayerPmCooldown {
    private val lastSentMs = ConcurrentHashMap<String, Long>()

    fun isWithinCooldown(
        player: String,
        cooldownMs: Long,
    ): Boolean {
        if (cooldownMs <= 0) return false
        val key = player.trim().lowercase()
        if (key.isEmpty()) return false
        val last = lastSentMs[key] ?: return false
        return System.currentTimeMillis() - last < cooldownMs
    }

    fun markSent(player: String) {
        val key = player.trim().lowercase()
        if (key.isEmpty()) return
        lastSentMs[key] = System.currentTimeMillis()
    }

    fun clear() {
        lastSentMs.clear()
    }
}
