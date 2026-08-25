package ru.arc.discord

import org.slf4j.LoggerFactory
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal fun interface DiscordLuckPermsEventSubscriber {
    fun subscribe(listener: (UUID) -> Unit): AutoCloseable?
}

/** Owns initial, periodic and debounced LuckPerms-driven role reconciliation. */
internal class DiscordRoleSyncCoordinator(
    private val config: DiscordVerificationConfig,
    private val verification: DiscordVerificationService,
    private val scheduler: TaskScheduler,
    private val eventSubscriber: DiscordLuckPermsEventSubscriber,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val pendingEvents = ConcurrentHashMap<UUID, ScheduledTask>()
    private val lastEventSyncAt = ConcurrentHashMap<UUID, Long>()
    private var periodicTask: ScheduledTask? = null
    private var eventSubscription: AutoCloseable? = null
    @Volatile
    private var started = false

    @Synchronized
    fun start() {
        if (started || !config.enabled) return
        started = true
        reconcileAll(DiscordRoleSyncTrigger.INITIAL)
        val intervalTicks = secondsToTicks(config.syncIntervalSeconds)
        periodicTask =
            scheduler.runTimerAsync(intervalTicks, intervalTicks) {
                reconcileAll(DiscordRoleSyncTrigger.PERIODIC)
            }
        if (config.luckPermsEventsEnabled) {
            eventSubscription =
                runCatching { eventSubscriber.subscribe(::onLuckPermsRecalculated) }
                    .onFailure { error ->
                        log.warn("Could not subscribe to LuckPerms role sync events: {}", error.javaClass.simpleName)
                    }
                    .getOrNull()
            if (eventSubscription == null) {
                log.warn("LuckPerms event-driven Discord role sync is unavailable; periodic reconciliation remains active")
            }
        }
    }

    private fun reconcileAll(trigger: DiscordRoleSyncTrigger) {
        if (!started) return
        verification.reconcileAll(trigger).whenComplete { _, error ->
            if (error != null) {
                log.warn("Discord role reconciliation trigger={} failed: {}", trigger.label, error.javaClass.simpleName)
            }
        }
    }

    @Synchronized
    private fun onLuckPermsRecalculated(playerUuid: UUID) {
        if (!started) return
        pendingEvents.remove(playerUuid)?.cancel()
        val delayTicks = millisToTicks(config.eventDebounceMillis)
        val task =
            scheduler.runLaterAsync(delayTicks) {
                pendingEvents.remove(playerUuid)
                if (!started) return@runLaterAsync
                val now = clock()
                val lastStarted = lastEventSyncAt[playerUuid] ?: 0L
                if (now - lastStarted < config.eventSuppressionSeconds * 1_000) return@runLaterAsync
                val link = verification.findByPlayerUuid(playerUuid) ?: return@runLaterAsync
                lastEventSyncAt[playerUuid] = now
                verification.reconcilePlayer(
                    playerUuid = playerUuid,
                    playerName = link.playerName,
                    trigger = DiscordRoleSyncTrigger.LUCKPERMS_EVENT,
                ).whenComplete { _, error ->
                    if (error != null) {
                        log.warn(
                            "LuckPerms-triggered Discord role reconciliation failed for {}: {}",
                            playerUuid,
                            error.javaClass.simpleName,
                        )
                    }
                }
            }
        pendingEvents[playerUuid] = task
    }

    @Synchronized
    override fun close() {
        started = false
        periodicTask?.cancel()
        periodicTask = null
        pendingEvents.values.forEach(ScheduledTask::cancel)
        pendingEvents.clear()
        lastEventSyncAt.clear()
        runCatching { eventSubscription?.close() }
            .onFailure { error -> log.warn("Could not close LuckPerms role sync subscription: {}", error.javaClass.simpleName) }
        eventSubscription = null
    }

    private fun secondsToTicks(seconds: Long): Long = (seconds * TICKS_PER_SECOND).coerceAtLeast(1L)

    private fun millisToTicks(millis: Long): Long = ((millis + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK).coerceAtLeast(1L)

    companion object {
        private val log = LoggerFactory.getLogger(DiscordRoleSyncCoordinator::class.java)
        private const val TICKS_PER_SECOND = 20L
        private const val MILLIS_PER_TICK = 50L
    }
}
