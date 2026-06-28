package ru.arc.ai.routing.ingress

import ru.arc.ai.routing.observe.BotReplyTracker

object MetaBuilder {
    private val skorinWord = Regex("""(?i)(?:^|[\s,.!?])@?скорен(?:[\s,.!?]|$)""")
    private val botWord = Regex("""(?i)(?:^|[\s,.!?])бот(?:[\s,.!?]|$)""")
    private val addscorenMention = Regex("""(?i)@addscoren\b""")

    fun directedAtBot(message: String): Boolean =
        skorinWord.containsMatchIn(message) ||
            botWord.containsMatchIn(message) ||
            addscorenMention.containsMatchIn(message)

    fun build(
        player: String,
        message: String,
        botReplyTracker: BotReplyTracker,
        continuationWindowSec: Int,
        timestampMs: Long = System.currentTimeMillis(),
        replyToPlayer: String? = null,
    ): InboundMeta {
        val lastAt = botReplyTracker.lastReplyAtMs
        val lastTo = botReplyTracker.lastReplyToPlayer
        val secSinceBot =
            if (lastAt > 0L) {
                ((timestampMs - lastAt).coerceAtLeast(0L) / 1000).toInt()
            } else {
                null
            }
        val samePlayerContinues =
            secSinceBot != null &&
                secSinceBot <= continuationWindowSec &&
                lastTo != null &&
                player.equals(lastTo, ignoreCase = true)
        val directed = directedAtBot(message)
        return InboundMeta(
            directedAtBot = directed,
            replyToBot =
                samePlayerContinues ||
                    (
                        directed &&
                            secSinceBot != null &&
                            secSinceBot <= continuationWindowSec
                    ),
            continuationWithBot = samePlayerContinues,
            secondsSinceBot = secSinceBot,
            replyToPlayer = replyToPlayer?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    fun buildDiscord(
        player: String,
        message: String,
        botReplyTracker: BotReplyTracker,
        continuationWindowSec: Int,
        replyToBot: Boolean,
        replyToPlayer: String?,
        timestampMs: Long = System.currentTimeMillis(),
    ): InboundMeta {
        val lastAt = botReplyTracker.lastReplyAtMs
        val secSinceBot =
            if (lastAt > 0L) {
                ((timestampMs - lastAt).coerceAtLeast(0L) / 1000).toInt()
            } else {
                null
            }
        val directed = directedAtBot(message) || replyToBot
        val samePlayerContinues =
            replyToBot ||
                (
                    secSinceBot != null &&
                        secSinceBot <= continuationWindowSec &&
                        player.equals(botReplyTracker.lastReplyToPlayer, ignoreCase = true)
                )
        return InboundMeta(
            directedAtBot = directed,
            replyToBot = replyToBot,
            continuationWithBot = samePlayerContinues,
            secondsSinceBot = secSinceBot,
            replyToPlayer = replyToPlayer?.takeIf { !replyToBot },
        )
    }
}
