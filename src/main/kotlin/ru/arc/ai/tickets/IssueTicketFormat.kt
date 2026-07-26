package ru.arc.ai.tickets

/**
 * Canonical Discord issue ticket title / body / dialog formatting.
 *
 * Title: `{topic} · {server}` or `[Закрыт] {topic} · {server}`
 * Description: short «Суть:» block only (no boilerplate «Игрок X сообщает…»)
 * Dialog: player ↔ Скорен lines, appended on updates
 */
object IssueTicketFormat {
    /** Java `(?i)` does not fold Cyrillic И/и — use explicit class. */
    private const val PLAYER_WORD = "[Ии]грок"

    private val playerPrefix = Regex("""^\[?\s*$PLAYER_WORD\s+[^\]]+\]\s*""")
    private val reporterBoilerplate =
        Regex("""^$PLAYER_WORD\s+[a-zA-Z0-9_]{2,16}\s+сообщает[,]?\s*(что\s+)?""")

    fun extractWorldSuffix(title: String): String? {
        val idx = title.lastIndexOf(" · ")
        if (idx < 0) return null
        return title.substring(idx + 3).trim().takeIf { it.isNotEmpty() }
    }

    fun normalizeTitle(
        raw: String,
        serverDisplay: String?,
        closed: Boolean = false,
    ): String {
        var topic =
            raw.trim()
                .removePrefix(IssueTicketTitles.CLOSED_PREFIX)
                .removePrefix("[Закрыт]")
                .trim()
        topic = playerPrefix.replace(topic, "").trim()
        topic = topic.replace(Regex("""\s+"""), " ").trim()
        if (topic.isEmpty()) topic = "баг сервера"

        val world =
            serverDisplay?.trim()?.takeIf { it.isNotEmpty() }?.let { PlayerWorldNames.displayProxyOrWorld(it) }
        topic = PlayerWorldNames.stripEmbeddedLocations(topic)
        val suffix = world?.takeIf { it != "неизвестно" }?.let { " · $it" }.orEmpty()
        val maxTopic = (100 - suffix.length).coerceAtLeast(20)
        topic = topic.take(maxTopic).trim()
        val withServer =
            if (world != null && suffix.isNotEmpty() && !topicEndsWithWorld(topic, world)) {
                topic + suffix
            } else {
                topic
            }
        return if (closed) IssueTicketTitles.markClosed(withServer) else withServer.take(100)
    }

    private fun topicEndsWithWorld(
        topic: String,
        world: String,
    ): Boolean {
        if (topic.endsWith(world, ignoreCase = true)) return true
        if (topic.endsWith(" · $world", ignoreCase = true)) return true
        return false
    }

    fun buildDescription(
        summary: String,
        reporter: String,
    ): String {
        val core = sanitizeSummary(summary, reporter)
        return "Суть: $core".take(2000)
    }

    fun sanitizeSummary(
        raw: String,
        reporter: String,
    ): String {
        var text = raw.trim().replace(Regex("""\s+"""), " ")
        text = reporterBoilerplate.replace(text, "").trim()
        text =
            Regex("""^$PLAYER_WORD\s+${Regex.escape(reporter)}\s+""").replace(text, "").trim()
        if (text.endsWith(".")) text = text.dropLast(1).trim()
        return text.ifBlank { raw.trim().take(500) }
    }

    fun formatDialogBlock(lines: String): String? = lines.trim().takeIf { it.isNotEmpty() }

    fun mergeDialog(
        existing: String?,
        appendLines: String,
    ): String {
        val parts =
            listOfNotNull(
                existing?.trim()?.takeIf { it.isNotEmpty() },
                appendLines.trim().takeIf { it.isNotEmpty() },
            )
        return parts.joinToString("\n").take(1024)
    }

    fun formatUpdateNote(
        agentNote: String?,
        triggerMessage: String?,
        dialogForReporter: String,
    ): String {
        val parts = mutableListOf<String>()
        triggerMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add("игрок: $it") }
        agentNote?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        dialogForReporter.trim().takeIf { it.isNotEmpty() }?.let { block ->
            val newLines =
                block.lines()
                    .map { it.trim() }
                    .filter { line -> parts.none { existing -> existing == line } }
            parts.addAll(newLines)
        }
        return parts.joinToString("\n").take(1024)
    }
}
