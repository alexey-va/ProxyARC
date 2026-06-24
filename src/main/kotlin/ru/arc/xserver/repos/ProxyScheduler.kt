package ru.arc.xserver.repos

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

abstract class ProxyScheduler : Runnable {
    private val service: ScheduledExecutorService = Executors.newScheduledThreadPool(8)
    private var future: ScheduledFuture<*>? = null
    @JvmField
    var cancelled: Boolean = false

    fun runTaskLater(delayTicks: Long): ProxyScheduler {
        service.schedule(this, delayTicks * 50, TimeUnit.MILLISECONDS)
        return this
    }

    fun runTaskTimer(delayTicks: Long, periodTicks: Long): ProxyScheduler {
        future = service.scheduleAtFixedRate(this, delayTicks * 50, periodTicks * 50, TimeUnit.MILLISECONDS)
        return this
    }

    fun cancel() {
        cancelled = true
        future?.cancel(false)
    }

    fun isCancelled(): Boolean = cancelled
}
