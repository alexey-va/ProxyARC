package ru.arc.ai.tickets

import org.slf4j.LoggerFactory
import ru.arc.config.ProxyConfigs
import ru.arc.velocity.Velocity
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object ForumTicketSync {
    private val log = LoggerFactory.getLogger(ForumTicketSync::class.java)
    private var scheduled: ScheduledFuture<*>? = null

    fun scheduleIfEnabled() {
        stop()
        val config = ProxyConfigs.module("assistant.yml")
        if (!config.bool("bug.context.sync-enabled", true)) return
        val bot = Velocity.discordBot ?: return
        val intervalSec = config.integer("bug.context.sync-interval-sec", 300).coerceIn(60, 3600)
        val executor = bot.scheduler()
        runCatching {
            scheduled =
                executor.scheduleWithFixedDelay(
                    {
                        runCatching { bot.syncForumTickets().join() }
                            .onFailure { log.warn("Forum ticket sync failed: {}", it.message) }
                    },
                    30L,
                    intervalSec.toLong(),
                    TimeUnit.SECONDS,
                )
            executor.execute {
                runCatching { bot.syncForumTickets().join() }
                    .onFailure { log.warn("Initial forum ticket sync failed: {}", it.message) }
            }
        }.onFailure {
            scheduled?.cancel(false)
            scheduled = null
            if (!executor.isShutdown) {
                log.warn("Could not schedule forum ticket sync: {}", it.message)
            }
        }
    }

    fun stop() {
        scheduled?.cancel(false)
        scheduled = null
    }
}
