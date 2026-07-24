package ru.arc.ai.routing.observe

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class BotReplyTracker {
    private val replyAtMs = AtomicLong(0L)
    private val replyToPlayerRef = AtomicReference<String?>(null)
    private val consecutiveReplies = AtomicInteger(0)

    val lastReplyAtMs: Long get() = replyAtMs.get()
    val lastReplyToPlayer: String? get() = replyToPlayerRef.get()
    val consecutiveRepliesToLastPlayer: Int get() = consecutiveReplies.get()

    @Synchronized
    fun record(
        toPlayer: String?,
        nowMs: Long = System.currentTimeMillis(),
        threadGapMs: Long = 90_000L,
    ) {
        val normalized = toPlayer?.trim()?.takeIf { it.isNotEmpty() }
        val previous = replyToPlayerRef.get()
        val previousAt = replyAtMs.get()
        consecutiveReplies.set(
            if (
                normalized != null &&
                previous != null &&
                normalized.equals(previous, ignoreCase = true) &&
                previousAt > 0L &&
                nowMs - previousAt in 0..threadGapMs
            ) {
                consecutiveReplies.get() + 1
            } else if (normalized != null) {
                1
            } else {
                0
            },
        )
        replyAtMs.set(nowMs)
        replyToPlayerRef.set(normalized)
    }

    fun reset() {
        replyAtMs.set(0L)
        replyToPlayerRef.set(null)
        consecutiveReplies.set(0)
    }
}
