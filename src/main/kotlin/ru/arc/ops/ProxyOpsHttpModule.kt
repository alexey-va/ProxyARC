package ru.arc.ops

import ru.arc.config.ProxyConfigs
import ru.arc.core.PluginModule

object ProxyOpsHttpModule : PluginModule {
    override val name = "ProxyOpsHttp"
    override val priority = 86

    private var server: ProxyOpsHttpServer? = null

    override fun init() {
        ProxyOpsHttpConfig.reload()
        server = ProxyOpsHttpServer().also { it.start() }
    }

    override fun shutdown() {
        server?.stop()
        server = null
    }

    override fun reload() {
        ProxyOpsHttpConfig.reload()
        server?.stop()
        server = ProxyOpsHttpServer().also { it.start() }
    }
}
