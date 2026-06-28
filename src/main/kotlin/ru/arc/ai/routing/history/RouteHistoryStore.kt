package ru.arc.ai.routing.history

import ru.arc.ai.routing.router.RouteIntent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

class RouteHistoryStore(
    private val maxPerPlayer: Int,
) {
    private val byPlayer = ConcurrentHashMap<String, ConcurrentLinkedDeque<RouteRecord>>()

    fun append(
        player: String,
        intent: RouteIntent,
        messageSnippet: String,
        confidence: Double,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val key = player.lowercase()
        val deque = byPlayer.computeIfAbsent(key) { ConcurrentLinkedDeque() }
        deque.addLast(
            RouteRecord(
                timestampMs = timestampMs,
                intent = intent,
                messageSnippet = messageSnippet,
                confidence = confidence,
            ),
        )
        while (deque.size > maxPerPlayer.coerceAtLeast(1)) {
            deque.pollFirst()
        }
    }

    fun snapshot(player: String, limit: Int = maxPerPlayer): List<RouteRecord> {
        val deque = byPlayer[player.lowercase()] ?: return emptyList()
        return deque.toList().takeLast(limit.coerceAtLeast(1))
    }

    fun clear() {
        byPlayer.clear()
    }
}
