package ru.arc.ai.routing.ingress

import ru.arc.ai.routing.observe.BotReplyTracker

object MetaBuilder {
    private val scorenAddress =
        Regex("""(?iu)(?:^|[,])\s*(?:(?:эй|слушай|ну)\s+)?@?(?:скорен|скроен)(?:[\s,.!?]|$)""")
    private val scorenThirdPersonPast =
        Regex(
            """(?iu)^\s*@?скорен\s+(?:(?:мне|ему|ей|им)\s+)?""" +
                """.{0,32}(?:помог|ответил|написал|сказал|подсказал)(?:\s|$)""",
        )
    private val scorenGreeting =
        Regex("""(?iu)(?:^|[\s,.!?])(?:ку|привет|даров|здаров|как\s+дела)\s*,?\s*@?скорен(?:[\s,.!?]|$)""")
    private val scorenTag = Regex("""(?iu)@скорен(?:[\s,.!?]|$)""")
    private val botWord = Regex("""(?i)(?:^|[,])\s*(?:эй\s+)?@?бот(?:[\s,.!?]|$)""")
    private val addscorenMention = Regex("""(?i)@addscoren\b""")

    fun directedAtBot(message: String): Boolean =
        !scorenThirdPersonPast.containsMatchIn(message) &&
            (
                scorenAddress.containsMatchIn(message) ||
            scorenGreeting.containsMatchIn(message) ||
            scorenTag.containsMatchIn(message) ||
            botWord.containsMatchIn(message) ||
                addscorenMention.containsMatchIn(message)
            )

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
            botRepliesInThread =
                if (samePlayerContinues) {
                    botReplyTracker.consecutiveRepliesToLastPlayer
                } else {
                    0
                },
        )
    }

    fun buildSimulation(
        player: String,
        message: String,
        botReplyTracker: BotReplyTracker,
        continuationWindowSec: Int,
        timestampMs: Long,
        replyToBot: Boolean,
        continuationWithBot: Boolean,
    ): InboundMeta {
        val inferred =
            build(
                player = player,
                message = message,
                botReplyTracker = botReplyTracker,
                continuationWindowSec = continuationWindowSec,
                timestampMs = timestampMs,
            )
        if (!replyToBot && !continuationWithBot) return inferred
        return inferred.copy(
            directedAtBot = directedAtBot(message) || replyToBot,
            replyToBot = replyToBot,
            continuationWithBot = continuationWithBot,
            botRepliesInThread =
                if (player.equals(botReplyTracker.lastReplyToPlayer, ignoreCase = true)) {
                    botReplyTracker.consecutiveRepliesToLastPlayer
                } else {
                    0
                },
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
            botRepliesInThread =
                if (samePlayerContinues) {
                    botReplyTracker.consecutiveRepliesToLastPlayer
                } else {
                    0
                },
        )
    }
}
