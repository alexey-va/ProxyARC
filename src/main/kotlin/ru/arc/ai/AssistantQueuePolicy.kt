package ru.arc.ai

/**
 * Queue rules that prevent delayed, stale Скорен replies during provider slowness.
 */
object AssistantQueuePolicy {
    fun shouldSupersede(
        queuedPlayer: String?,
        queuedMode: AssistantRunMode,
        incomingPlayer: String?,
        incomingMode: AssistantRunMode,
    ): Boolean =
        incomingMode == AssistantRunMode.CHAT &&
            queuedMode == incomingMode &&
            !incomingPlayer.isNullOrBlank() &&
            queuedPlayer.equals(incomingPlayer, ignoreCase = true)

    fun isExpired(
        enqueuedAtMs: Long,
        nowMs: Long,
        maxQueueAgeMs: Long,
    ): Boolean =
        maxQueueAgeMs >= 0L &&
            nowMs - enqueuedAtMs >= maxQueueAgeMs
}
