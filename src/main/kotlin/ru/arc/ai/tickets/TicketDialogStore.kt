package ru.arc.ai.tickets

import java.util.Deque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/** Per-player dialog lines for Discord ticket embeds (not shared across reporters). */
object TicketDialogStore {
    private val byPlayer = ConcurrentHashMap<String, Deque<String>>()

    fun record(
        player: String,
        line: String,
    ) {
        val key = player.trim().lowercase()
        if (key.isEmpty()) return
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        val deque = byPlayer.computeIfAbsent(key) { ConcurrentLinkedDeque() }
        deque.addLast(trimmed)
        while (deque.size > 40) {
            deque.pollFirst()
        }
    }

    fun snapshot(
        player: String,
        maxLines: Int = 15,
    ): String {
        val key = player.trim().lowercase()
        if (key.isEmpty()) return ""
        val deque = byPlayer[key] ?: return ""
        if (deque.isEmpty()) return ""
        return deque.toList().takeLast(maxLines.coerceAtLeast(1)).joinToString("\n")
    }

    fun clear(player: String) {
        val key = player.trim().lowercase()
        if (key.isEmpty()) return
        byPlayer.remove(key)
    }

    fun clearAll() {
        byPlayer.clear()
    }
}
