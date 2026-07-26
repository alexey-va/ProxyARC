package ru.arc.core.modules

import ru.arc.FirstJoinData
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.velocity.Velocity

// ==================== Priority 50-59: Persistence ====================

object FirstJoinModule : PluginModule {
    override val name = "FirstJoin"
    override val priority = 50

    override fun init() {
        val data = FirstJoinData()
        data.load()
        Velocity.firstJoinData = data
    }

    override fun reload() {}

    override fun shutdown() {
        Velocity.firstJoinData?.save()
        Velocity.firstJoinData = null
    }
}

object SaveModule : PluginModule {
    override val name = "Save"
    override val priority = 55

    private var saveTask: ScheduledTask? = null

    override fun init() {
        saveTask?.cancel()
        saveTask =
            Tasks.scheduler.runTimerAsync(1200L, 1200L) {
                Velocity.firstJoinData?.save()
            }
    }

    override fun reload() {
        init()
    }

    override fun shutdown() {
        saveTask?.cancel()
        saveTask = null
        Velocity.firstJoinData?.save()
    }
}
