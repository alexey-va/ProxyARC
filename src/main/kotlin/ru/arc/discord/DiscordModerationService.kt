package ru.arc.discord

import litebans.api.Entry
import litebans.api.Events
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.UUID

/** Read-only LiteBans event bridge. It never executes moderation commands. */
internal class DiscordModerationService(
    private val notifications: DiscordNotificationService,
    private val messages: DiscordIntegrationMessages,
    private val playerNameByUuid: (UUID) -> String?,
) : AutoCloseable {
    @Volatile private var registered = false

    private val listener =
        object : Events.Listener() {
            override fun entryAdded(entry: Entry) {
                publish(entry, messages.text("moderation-status-issued"))
            }

            override fun entryRemoved(entry: Entry) {
                val status =
                    messages.text(
                        if (entry.isExpired(System.currentTimeMillis())) {
                            "moderation-status-expired"
                        } else {
                            "moderation-status-removed"
                        },
                    )
                publish(entry, status)
            }
        }

    fun start() {
        if (registered) return
        runCatching {
            Events.get().register(listener)
            registered = true
            log.info("Discord LiteBans event bridge registered")
        }.onFailure { error ->
            log.info("Discord LiteBans event bridge is unavailable: {}", error.javaClass.simpleName)
        }
    }

    private fun publish(
        entry: Entry,
        status: String,
    ) {
        val uuid = runCatching { UUID.fromString(entry.uuid) }.getOrNull() ?: return
        val playerName = playerNameByUuid(uuid) ?: "unknown"
        val type = entry.type.lowercase(Locale.ROOT).take(24)
        notifications.notifyPunishment(uuid, playerName, type, status)
        notifications.alert(
            messages.text(
                "moderation-alert",
                "type" to DiscordTextSafety.markdown(type, 24),
                "status" to DiscordTextSafety.markdown(status, 40),
                "player" to DiscordTextSafety.markdown(playerName, 16),
                "duration" to
                    when {
                        entry.isPermanent -> messages.text("moderation-duration-permanent")
                        entry.dateEnd > 0 -> messages.text("moderation-duration-until", "ends_at" to (entry.dateEnd / 1_000).toString())
                        else -> ""
                    },
                "moderator" to
                    entry.executorName?.takeIf { it.isNotBlank() }?.let {
                        messages.text("moderation-executor", "executor" to DiscordTextSafety.markdown(it, 40))
                    }.orEmpty(),
            ),
        )
    }

    override fun close() {
        if (!registered) return
        runCatching { Events.get().unregister(listener) }
            .onFailure { log.debug("Could not unregister LiteBans event bridge", it) }
        registered = false
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordModerationService::class.java)
    }
}
