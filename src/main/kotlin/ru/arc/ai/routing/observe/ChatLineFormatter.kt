package ru.arc.ai.routing.observe

import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.ingress.InboundMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ChatLineFormatter(
    private val template: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun format(
        message: InboundMessage,
        meta: InboundMeta,
        botReplyTracker: BotReplyTracker,
    ): String {
        val time = timeFormat.format(Instant.ofEpochMilli(message.timestampMs).atZone(zone))
        val delta = formatDelta(message.timestampMs, botReplyTracker)
        val flags = formatFlags(meta)
        return template
            .replace("%time%", time)
            .replace("%delta%", delta)
            .replace("%flags%", flags)
            .replace("%player%", message.player)
            .replace("%message%", message.displayText)
    }

    fun formatBotLine(
        message: String,
        botName: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): String {
        val time = timeFormat.format(Instant.ofEpochMilli(timestampMs).atZone(zone))
        return template
            .replace("%time%", time)
            .replace("%delta%", "—")
            .replace("%flags%", "[скорен] ")
            .replace("%player%", botName)
            .replace("%message%", message)
    }

    private fun formatDelta(timestampMs: Long, botReplyTracker: BotReplyTracker): String =
        ChatObserveFlags.formatDelta(timestampMs, botReplyTracker)

    private fun formatFlags(meta: InboundMeta): String = ChatObserveFlags.formatFlags(meta)
}

/** Shared delta/flags for observe lines (router log + assistant context). */
internal object ChatObserveFlags {
    fun formatDelta(timestampMs: Long, botReplyTracker: BotReplyTracker): String {
        val last = botReplyTracker.lastReplyAtMs
        if (last <= 0L) return "—"
        val sec = ((timestampMs - last).coerceAtLeast(0L)) / 1000
        return "+${sec}s"
    }

    fun formatFlags(meta: InboundMeta): String {
        val flags = mutableListOf<String>()
        if (meta.replyToBot) flags.add("ответ скорену")
        if (meta.continuationWithBot && !meta.replyToBot) flags.add("продолжение с скореном")
        if (meta.directedAtBot) flags.add("→скорен")
        meta.replyToPlayer?.let { flags.add("к $it") }
        meta.secondsSinceBot?.let { flags.add("после скорена ${it}s") }
        return if (flags.isEmpty()) "" else "[${flags.joinToString(", ")}] "
    }
}
