package ru.arc.core.modules

import ru.arc.FirstJoinData
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// ==================== Priority 50-59: Persistence ====================

object FirstJoinModule : PluginModule {
    override val name = "FirstJoin"
    override val priority = 50

    override fun init() {
        Velocity.firstJoinData = FirstJoinData()
        Velocity.firstJoinData!!.load()
    }

    override fun shutdown() {
        Velocity.firstJoinData?.save()
        Velocity.firstJoinData = null
    }
}

object SaveModule : PluginModule {
    override val name = "Save"
    override val priority = 55

    private val saveService = Executors.newScheduledThreadPool(1)
    private var saveTask: ScheduledFuture<*>? = null

    override fun init() {
        saveTask?.cancel(false)
        saveTask =
            saveService.scheduleAtFixedRate(
                { Velocity.firstJoinData?.save() },
                60,
                60,
                TimeUnit.SECONDS,
            )
    }

    override fun shutdown() {
        saveTask?.cancel(false)
        saveTask = null
        Velocity.firstJoinData?.save()
        saveService.shutdown()
    }
}
