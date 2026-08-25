package ru.arc.discord

import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal data class DiscordEventParticipationResult(
    val event: DiscordGameEvent,
    val joined: Boolean,
    val roleChanged: Boolean,
)

internal data class DiscordEventFinishResult(
    val event: DiscordGameEvent,
    val failedDiscordOperations: Int,
)

internal class DiscordGameEventService(
    private val session: DiscordSession,
    private val config: DiscordIntegrationConfig,
    private val store: DiscordIntegrationStore,
    private val notifications: DiscordNotificationService,
    private val scheduler: ScheduledExecutorService,
    private val identityByPlayerName: (String) -> DiscordIdentityLink?,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    @Volatile private var reminderTask: ScheduledFuture<*>? = null
    private val operationLock = Any()
    private var operationTail = CompletableFuture.completedFuture<Void>(null)

    fun start() {
        if (reminderTask != null || config.eventReminderMinutes.isEmpty()) return
        reminderTask =
            scheduler.scheduleWithFixedDelay(
                ::publishDueReminders,
                30,
                30,
                TimeUnit.SECONDS,
            )
    }

    fun create(
        name: String,
        description: String,
        startsAt: Long,
    ): CompletableFuture<DiscordGameEvent> = enqueue {
        val channel = config.announcementsChannelId?.let { session.jda()?.getTextChannelById(it) }
            ?: return@enqueue CompletableFuture.failedFuture(IllegalStateException("announcements channel unavailable"))
        val guild = session.jda()?.getGuildById(config.guildId)
        if (config.participantRoleId?.let { guild?.getRoleById(it) } == null) {
            return@enqueue CompletableFuture.failedFuture(IllegalStateException("participant role unavailable"))
        }
        val event = store.createEvent(name.take(80), description.take(1_000), startsAt)
        val result = CompletableFuture<DiscordGameEvent>()
        channel.sendMessage(announcementData(event)).queue(
            { message ->
                runCatching { store.setEventAnnouncement(event.id, message.id) ?: event }
                    .onSuccess(result::complete)
                    .onFailure { error ->
                        message.delete().queue({}, {})
                        runCatching { store.cancelEvent(event.id) }
                        result.completeExceptionally(error)
                    }
            },
            { error ->
                runCatching { store.cancelEvent(event.id) }
                result.completeExceptionally(error)
            },
        )
        result
    }

    fun toggleParticipation(discordUserId: String): CompletableFuture<DiscordEventParticipationResult?> = enqueue {
        val active = store.activeEvent() ?: return@enqueue CompletableFuture.completedFuture(null)
        val changed = store.toggleEventParticipant(active.id, discordUserId)
            ?: return@enqueue CompletableFuture.completedFuture(null)
        val (event, joined) = changed
        val result = CompletableFuture<DiscordEventParticipationResult?>()
        modifyParticipantRole(discordUserId, joined).whenComplete { _, error ->
            if (error != null) {
                val reverted = runCatching { store.toggleEventParticipant(event.id, discordUserId)?.first }.getOrNull() ?: event
                updateAnnouncement(reverted).whenComplete { _, _ -> result.completeExceptionally(error) }
            } else {
                updateAnnouncement(event).whenComplete { _, updateError ->
                    if (updateError != null) {
                        log.debug("Could not update Discord event announcement: {}", updateError.javaClass.simpleName)
                    }
                    result.complete(DiscordEventParticipationResult(event, joined, roleChanged = true))
                }
            }
        }
        result
    }

    fun finish(
        winnerPlayerName: String?,
        cancelled: Boolean,
    ): CompletableFuture<DiscordEventFinishResult?> = enqueue {
        val effectiveWinner = winnerPlayerName.takeUnless { cancelled }
        val guild = session.jda()?.getGuildById(config.guildId)
            ?: return@enqueue CompletableFuture.failedFuture(IllegalStateException("guild unavailable"))
        val participantRole = config.participantRoleId?.let(guild::getRoleById)
            ?: return@enqueue CompletableFuture.failedFuture(IllegalStateException("participant role unavailable"))
        val winner = if (effectiveWinner != null) identityByPlayerName(effectiveWinner) else null
        val winnerRole = if (!cancelled) config.winnerRoleId?.let(guild::getRoleById) else null
        if (!cancelled && (winner == null || winnerRole == null)) {
            return@enqueue CompletableFuture.failedFuture(IllegalStateException("winner identity or role unavailable"))
        }
        val event = store.finishEvent(effectiveWinner, cancelled)
            ?: return@enqueue CompletableFuture.completedFuture(null)
        val operations = event.participantDiscordIds.mapTo(mutableListOf()) { userId ->
            guild.removeRoleFromMember(UserSnowflake.fromId(userId), participantRole).submit()
        }
        if (winner != null && winnerRole != null) {
            operations += guild.addRoleToMember(UserSnowflake.fromId(winner.discordUserId), winnerRole).submit()
        }
        operations += finishAnnouncement(event, cancelled)
        val outcomes = operations.map { operation -> operation.handle { _, error -> error } }
        CompletableFuture.allOf(*outcomes.toTypedArray()).thenApply {
            val failures = outcomes.count { it.join() != null }
            if (failures > 0) {
                log.warn("Discord event {} finished with {} failed Discord operations", event.id, failures)
            }
            DiscordEventFinishResult(event, failures)
        }
    }

    fun activeEvent(): DiscordGameEvent? = store.activeEvent()

    fun migrateParticipant(
        previousDiscordUserId: String,
        newDiscordUserId: String,
    ): CompletableFuture<Void> = enqueue {
        val active = store.activeEvent()
        if (active == null || previousDiscordUserId !in active.participantDiscordIds) {
            return@enqueue CompletableFuture.completedFuture<Void>(null)
        }
        val newWasAdded = newDiscordUserId !in active.participantDiscordIds
        if (newWasAdded) {
            store.toggleEventParticipant(active.id, newDiscordUserId)?.first
                ?: return@enqueue CompletableFuture.failedFuture(IllegalStateException("active event changed"))
        }
        modifyParticipantRole(newDiscordUserId, joined = true).handle { _, error -> error }.thenCompose { addError ->
            if (addError == null) {
                CompletableFuture.completedFuture<Void>(null)
            } else if (!newWasAdded) {
                CompletableFuture.failedFuture<Void>(addError)
            } else {
                runCatching { store.toggleEventParticipant(active.id, newDiscordUserId) }
                modifyParticipantRole(newDiscordUserId, joined = false).handle { _, _ -> null }.thenCompose {
                    CompletableFuture.failedFuture<Void>(addError)
                }
            }
        }.thenCompose {
            modifyParticipantRole(previousDiscordUserId, joined = false)
        }.thenCompose {
            val migrated = store.toggleEventParticipant(active.id, previousDiscordUserId)?.first
                ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("active event changed"))
            updateAnnouncement(migrated).exceptionally { error ->
                log.debug("Could not update migrated event participant: {}", error.javaClass.simpleName)
                null
            }
        }
    }

    fun removeParticipant(discordUserId: String): CompletableFuture<Void> = enqueue {
        val active = store.activeEvent()
        if (active == null || discordUserId !in active.participantDiscordIds) {
            return@enqueue CompletableFuture.completedFuture<Void>(null)
        }
        modifyParticipantRole(discordUserId, joined = false).thenCompose {
            val updated = store.toggleEventParticipant(active.id, discordUserId)?.first
                ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("active event changed"))
            updateAnnouncement(updated).exceptionally { error ->
                log.debug("Could not update unlinked event participant: {}", error.javaClass.simpleName)
                null
            }
        }
    }

    private fun modifyParticipantRole(
        discordUserId: String,
        joined: Boolean,
    ): CompletableFuture<Void> {
        val guild = session.jda()?.getGuildById(config.guildId)
            ?: return CompletableFuture.failedFuture(IllegalStateException("guild unavailable"))
        val role = config.participantRoleId?.let(guild::getRoleById)
            ?: return CompletableFuture.failedFuture(IllegalStateException("participant role unavailable"))
        val action =
            if (joined) guild.addRoleToMember(UserSnowflake.fromId(discordUserId), role)
            else guild.removeRoleFromMember(UserSnowflake.fromId(discordUserId), role)
        return action.submit()
    }

    private fun announcementData(event: DiscordGameEvent) =
        MessageCreateBuilder()
            .setContent(announcementText(event))
            .setComponents(listOf(eventButtons()))
            .setAllowedMentions(emptySet())
            .build()

    private fun announcementText(event: DiscordGameEvent): String =
        config.messages.text(
            "event-announcement",
            "event" to DiscordTextSafety.markdown(event.name, 80),
            "description" to DiscordTextSafety.markdown(event.description, 1_000),
            "starts_at" to (event.startsAt / 1_000).toString(),
            "participants" to event.participantDiscordIds.size.toString(),
        )

    private fun eventButtons(): ActionRow =
        ActionRow.of(
            Button.success(BUTTON_JOIN, config.messages.text("event-join")),
            Button.secondary(BUTTON_LEAVE, config.messages.text("event-leave")),
        )

    private fun updateAnnouncement(event: DiscordGameEvent): CompletableFuture<Void> {
        val channel = config.announcementsChannelId?.let { session.jda()?.getTextChannelById(it) }
            ?: return CompletableFuture.failedFuture(IllegalStateException("announcements channel unavailable"))
        val messageId = event.announcementMessageId
            ?: return CompletableFuture.failedFuture(IllegalStateException("announcement message unavailable"))
        val data =
            MessageEditBuilder()
                .setContent(announcementText(event))
                .setComponents(listOf(eventButtons()))
                .setAllowedMentions(emptySet())
                .build()
        return channel.editMessageById(messageId, data).submit().thenApply { null }
    }

    private fun finishAnnouncement(
        event: DiscordGameEvent,
        cancelled: Boolean,
    ): CompletableFuture<Void> {
        val channel = config.announcementsChannelId?.let { session.jda()?.getTextChannelById(it) }
            ?: return CompletableFuture.failedFuture(IllegalStateException("announcements channel unavailable"))
        val messageId = event.announcementMessageId
            ?: return CompletableFuture.failedFuture(IllegalStateException("announcement message unavailable"))
        val content =
            if (cancelled) {
                config.messages.text("event-cancelled", "event" to DiscordTextSafety.markdown(event.name, 80))
            } else {
                config.messages.text(
                    "event-result",
                    "event" to DiscordTextSafety.markdown(event.name, 80),
                    "winner" to DiscordTextSafety.markdown(event.winnerPlayerName ?: config.messages.text("label-not-specified"), 40),
                )
            }
        return channel.editMessageById(
            messageId,
            MessageEditBuilder().setContent(content).setComponents(emptyList()).setAllowedMentions(emptySet()).build(),
        ).submit().thenApply { null }
    }

    private fun <T> enqueue(operation: () -> CompletableFuture<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        synchronized(operationLock) {
            operationTail =
                operationTail.handle { _, _ -> null }
                    .thenCompose {
                        runCatching(operation).getOrElse { CompletableFuture.failedFuture(it) }
                    }.handle { value, error ->
                        if (error == null) result.complete(value) else result.completeExceptionally(error)
                        null
                    }
        }
        return result
    }

    private fun publishDueReminders() {
        runCatching {
            val now = clock()
            store.remindersDue(now, config.eventReminderMinutes).forEach { (event, minutes) ->
                val message =
                    config.messages.text(
                        "event-reminder",
                        "event" to DiscordTextSafety.markdown(event.name, 80),
                        "starts_at" to (event.startsAt / 1_000).toString(),
                    )
                notifications.notifyEventParticipants(event, message)
                store.markReminderSent(event.id, minutes)
            }
        }.onFailure { error ->
            log.warn("Discord event reminder pass failed: {}", error.javaClass.simpleName)
        }
    }

    override fun close() {
        reminderTask?.cancel(false)
        reminderTask = null
    }

    companion object {
        const val BUTTON_JOIN = "rc:event:join"
        const val BUTTON_LEAVE = "rc:event:leave"
        private val log = LoggerFactory.getLogger(DiscordGameEventService::class.java)
    }
}
