package ru.arc.ai.routing.history

import ru.arc.ai.routing.router.RouteIntent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class RouteRecord(
    val timestampMs: Long,
    val intent: RouteIntent,
    val messageSnippet: String,
    val confidence: Double,
) {
    fun formatLine(zone: ZoneId = ZoneId.systemDefault()): String {
        val time =
            DateTimeFormatter.ofPattern("HH:mm:ss")
                .format(Instant.ofEpochMilli(timestampMs).atZone(zone))
        val snippet =
            messageSnippet
                .replace('\n', ' ')
                .take(60)
        return "$time ${intent.wireName()} conf=${"%.2f".format(confidence)} «$snippet»"
    }
}
