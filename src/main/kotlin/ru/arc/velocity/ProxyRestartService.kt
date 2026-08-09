package ru.arc.velocity

import net.kyori.adventure.text.Component
import ru.arc.Utils
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import java.time.Duration

data class ProxyRestartPlan(
    val delay: Duration,
    val initiatedBy: String,
    val playersAtSchedule: Int,
)

sealed class ProxyRestartScheduleResult {
    data class Scheduled(
        val plan: ProxyRestartPlan,
    ) : ProxyRestartScheduleResult()

    data object AlreadyPending : ProxyRestartScheduleResult()
}

/**
 * Proxy-owned restart countdown. Process supervision remains in scripts/mc restart;
 * this service only handles player-facing warnings and graceful Velocity shutdown.
 */
class ProxyRestartService(
    private val scheduler: TaskScheduler,
    private val playerCount: () -> Int,
    private val broadcast: (Component) -> Unit,
    private val shutdown: (Component) -> Unit,
    private val eventLog: (String) -> Unit = {},
) {
    private var pending: ProxyRestartPlan? = null
    private val tasks = mutableListOf<ScheduledTask>()

    @Synchronized
    fun schedule(
        delay: Duration,
        initiatedBy: String,
    ): ProxyRestartScheduleResult {
        require(!delay.isZero && !delay.isNegative) { "Restart delay must be positive" }
        if (pending != null) return ProxyRestartScheduleResult.AlreadyPending

        val seconds = delay.toSeconds().coerceAtLeast(1)
        val plan = ProxyRestartPlan(delay, initiatedBy, playerCount().coerceAtLeast(0))
        pending = plan
        notifyPlayers(seconds)

        WARNING_SECONDS
            .asSequence()
            .filter { it < seconds }
            .forEach { secondsRemaining ->
                val waitTicks = (seconds - secondsRemaining) * TICKS_PER_SECOND
                tasks +=
                    scheduler.runLater(waitTicks) {
                        notifyIfPending(plan, secondsRemaining)
                    }
            }

        tasks +=
            scheduler.runLater(seconds * TICKS_PER_SECOND) {
                executeRestart(plan, seconds)
            }

        eventLog("scheduled delay=${seconds}s players=${plan.playersAtSchedule} by=$initiatedBy")
        return ProxyRestartScheduleResult.Scheduled(plan)
    }

    @Synchronized
    fun cancel(initiatedBy: String): Boolean {
        if (pending == null) return false
        pending = null
        tasks.forEach { it.cancel() }
        tasks.clear()
        if (playerCount() > 0) broadcast(Utils.mm(CANCEL_MESSAGE))
        eventLog("cancelled by=$initiatedBy")
        return true
    }

    @Synchronized
    fun shutdownModule() {
        pending = null
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    @Synchronized
    fun pendingPlan(): ProxyRestartPlan? = pending

    @Synchronized
    private fun notifyIfPending(
        plan: ProxyRestartPlan,
        secondsRemaining: Long,
    ) {
        if (pending === plan) notifyPlayers(secondsRemaining)
    }

    @Synchronized
    private fun executeRestart(
        plan: ProxyRestartPlan,
        seconds: Long,
    ) {
        val active = pending?.takeIf { it === plan } ?: return
        pending = null
        tasks.clear()
        val online = playerCount().coerceAtLeast(0)
        eventLog("shutdown delay=${seconds}s players=$online by=${active.initiatedBy}")
        shutdown(Utils.mm(KICK_MESSAGE))
    }

    private fun notifyPlayers(secondsRemaining: Long) {
        val online = playerCount().coerceAtLeast(0)
        if (online == 0) return
        val message = WARNING_MESSAGE.replace("<time>", formatDelay(secondsRemaining))
        broadcast(Utils.mm(message))
        eventLog("warning remaining=${secondsRemaining}s players=$online")
    }

    companion object {
        private const val TICKS_PER_SECOND = 20L
        private val WARNING_SECONDS = listOf(30L, 10L, 5L, 4L, 3L, 2L, 1L)
        private const val WARNING_MESSAGE = "<red>[Прокси] <gray>Перезапуск через <white><time>"
        private const val CANCEL_MESSAGE = "<green>[Прокси] <gray>Перезапуск отменён"
        private const val KICK_MESSAGE = "<red>Прокси перезапускается. Переподключитесь через несколько секунд."

        internal fun formatDelay(seconds: Long): String =
            if (seconds >= 60 && seconds % 60L == 0L) "${seconds / 60}м" else "${seconds}с"
    }
}

object ProxyRestartDuration {
    private val PATTERN = Regex("^([0-9]+)([smh]?)$", RegexOption.IGNORE_CASE)
    private val MIN_DELAY = Duration.ofSeconds(5)
    private val MAX_DELAY = Duration.ofMinutes(30)

    fun parse(raw: String): Duration? {
        val match = PATTERN.matchEntire(raw.trim()) ?: return null
        val value = match.groupValues[1].toLongOrNull() ?: return null
        val duration =
            runCatching {
                when (match.groupValues[2].lowercase()) {
                    "h" -> Duration.ofHours(value)
                    "m" -> Duration.ofMinutes(value)
                    else -> Duration.ofSeconds(value)
                }
            }.getOrNull() ?: return null
        return duration.takeIf { it >= MIN_DELAY && it <= MAX_DELAY }
    }
}
