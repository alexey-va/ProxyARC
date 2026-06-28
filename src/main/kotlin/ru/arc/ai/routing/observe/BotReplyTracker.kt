package ru.arc.ai.routing.observe

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class BotReplyTracker {
    private val replyAtMs = AtomicLong(0L)
    private val replyToPlayerRef = AtomicReference<String?>(null)

    val lastReplyAtMs: Long get() = replyAtMs.get()
    val lastReplyToPlayer: String? get() = replyToPlayerRef.get()

    fun record(toPlayer: String?) {
        replyAtMs.set(System.currentTimeMillis())
        replyToPlayerRef.set(toPlayer?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun reset() {
        replyAtMs.set(0L)
        replyToPlayerRef.set(null)
    }
}
