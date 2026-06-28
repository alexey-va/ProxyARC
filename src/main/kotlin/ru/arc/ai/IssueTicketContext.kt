package ru.arc.ai

import ru.arc.velocity.Velocity
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class IssueTicketContext(
    val reporter: String,
    val backendServer: String,
    val displayServer: String,
    val reportedAt: String,
    val triggerMessage: String?,
    val chatSnippet: String?,
    val source: String = BOT_SOURCE,
) {
    companion object {
        const val BOT_SOURCE = "Пивопровод"

        private val DISPLAY_NAMES =
            mapOf(
                "spawn" to "Спавн",
                "classic" to "Спавн",
                "survival" to "Survival",
                "classic_survival" to "Survival",
                "parkour" to "Parkour",
                "discord" to "Discord",
                "velocity" to "Velocity",
                "proxy" to "Velocity",
                "minecraft-server" to "Velocity",
            )

        private val DATE_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm (MSK)")

        fun resolveBackendServer(playerName: String): String? {
            val proxy = Velocity.proxyServer
            if (proxy != null) {
                val player = proxy.getPlayer(playerName).orElse(null)
                if (player != null) {
                    return player.currentServer.map { it.serverInfo.name }.orElse(null)
                }
            }
            return Velocity.playerListAnnouncer?.serverForUsername(playerName)
        }

        fun displayServer(raw: String?): String {
            if (raw.isNullOrBlank()) return "неизвестно"
            val key = raw.trim().lowercase()
            return DISPLAY_NAMES[key] ?: raw.trim()
        }

        fun formatNow(): String =
            OffsetDateTime.now(ZoneId.of("Europe/Moscow")).format(DATE_FMT)

        fun isDiscordReporter(playerName: String): Boolean =
            playerName.equals("discord", ignoreCase = true) ||
                playerName.equals("bug-scanner", ignoreCase = true)

        fun build(
            assistant: Assistant,
            reporter: String,
            llmServerHint: String?,
        ): IssueTicketContext {
            val resolved = resolveBackendServer(reporter)
            val hint = llmServerHint?.trim()?.takeIf { it.isNotEmpty() }
            val rawServer =
                when {
                    resolved != null -> resolved
                    isDiscordReporter(reporter) -> "discord"
                    hint != null -> hint
                    else -> "неизвестно"
                }
            val display = displayServer(rawServer)
            val snippet = assistant.snapshotChatObservations(maxLines = 10)
            return IssueTicketContext(
                reporter = reporter,
                backendServer = rawServer,
                displayServer = display,
                reportedAt = formatNow(),
                triggerMessage = assistant.lastTriggerMessage,
                chatSnippet = snippet.takeIf { it.isNotBlank() },
            )
        }
    }

    fun serverFieldValue(): String =
        if (backendServer.equals(displayServer, ignoreCase = true)) {
            displayServer
        } else {
            "$displayServer ($backendServer)"
        }
}
