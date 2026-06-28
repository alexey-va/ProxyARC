package ru.arc.ai.routing.ingress

data class InboundMessage(
    val player: String,
    val rawText: String,
    val displayText: String,
    val timestampMs: Long,
    val server: String?,
    val source: Source,
) {
    /** In-game chat with assistant only when message used global `!` prefix. */
    fun allowsChatRouting(): Boolean =
        when (source) {
            Source.GAME -> rawText.startsWith("!")
            Source.DISCORD -> true
        }

    enum class Source {
        GAME,
        DISCORD,
    }
}
