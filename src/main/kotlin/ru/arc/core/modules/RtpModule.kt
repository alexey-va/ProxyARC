package ru.arc.core.modules

import ru.arc.core.PluginModule
import ru.arc.rtp.ProxyRtpConfig
import ru.arc.rtp.RtpRequestManager
import ru.arc.velocity.Velocity

object RtpModule : PluginModule {
    override val name = "RTP"
    override val priority = 55

    private var listener: RtpRequestManager? = null

    override fun init() {
        val plugin = Velocity.requirePlugin()
        val server = Velocity.requireProxyServer()
        val manager = RtpRequestManager(server, ProxyRtpConfig())
        server.channelRegistrar.register(RtpRequestManager.CHANNEL)
        server.eventManager.register(plugin, manager)
        listener = manager
        Velocity.rtpRequestManager = manager
    }

    override fun shutdown() {
        val server = Velocity.proxyServer
        listener?.let {
            it.shutdown()
            server?.eventManager?.unregisterListener(Velocity.plugin, it)
        }
        server?.channelRegistrar?.unregister(RtpRequestManager.CHANNEL)
        listener = null
        Velocity.rtpRequestManager = null
    }

    override fun reload() {}
}
