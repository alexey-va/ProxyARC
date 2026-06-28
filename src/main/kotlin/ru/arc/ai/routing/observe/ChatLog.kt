package ru.arc.ai.routing.observe

import java.util.concurrent.ConcurrentLinkedDeque

class ChatLog(
    private val maxLines: Int,
) {
    private val lines = ConcurrentLinkedDeque<String>()

    fun append(line: String) {
        if (line.isBlank()) return
        lines.addLast(line.trim())
        while (lines.size > maxLines.coerceAtLeast(1)) {
            lines.pollFirst()
        }
    }

    fun snapshot(limit: Int = maxLines): List<String> {
        val cap = limit.coerceAtLeast(1)
        if (lines.isEmpty()) return emptyList()
        return lines.toList().takeLast(cap)
    }

    fun clear() {
        lines.clear()
    }
}
