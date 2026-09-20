package ru.arc.ops

import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.velocity.Velocity

object ProxyOpsHttpModule : PluginModule {
    override val name = "ProxyOpsHttp"
    override val priority = 86

    private var server: ProxyOpsHttpServer? = null
    private var healthTask: ScheduledTask? = null
    private var dependencyTask: ScheduledTask? = null

    override fun init() {
        ProxyOpsHttpConfig.reload()
        server = ProxyOpsHttpServer().also { it.start() }
        healthTask?.cancel()
        dependencyTask?.cancel()
        dependencyTask = Tasks.scheduler.runTimerAsync(20L, 600L) {
            Velocity.discordBot?.sampleTransportHealth()
        }
        healthTask =
            Tasks.scheduler.runTimerAsync(HEALTH_REPORT_TICKS, HEALTH_REPORT_TICKS) {
                Velocity.requireLogger().info(ProxyRuntimeHealth.line())
            }
    }

    override fun shutdown() {
        healthTask?.cancel()
        healthTask = null
        dependencyTask?.cancel()
        dependencyTask = null
        server?.stop()
        server = null
    }

    override fun reload() {
        shutdown()
        init()
    }

    private const val HEALTH_REPORT_TICKS = 1_200L
}
