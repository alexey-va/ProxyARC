package ru.arc.ai.routing.ingress

data class InboundMeta(
    val directedAtBot: Boolean,
    val replyToBot: Boolean,
    val continuationWithBot: Boolean,
    val secondsSinceBot: Int?,
    val replyToPlayer: String?,
    val botRepliesInThread: Int = 0,
)
