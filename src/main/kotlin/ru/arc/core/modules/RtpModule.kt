package ru.arc.core.modules

import ru.arc.core.PluginModule
import ru.arc.rtp.BackendRtpMessageHandler
import ru.arc.rtp.ProxyRtpConfig
import ru.arc.rtp.RtpRequestManager
import ru.arc.velocity.Velocity

object RtpModule : PluginModule {
    override val name = "RTP"
    override val priority = 55

    private var manager: RtpRequestManager? = null
    private var backendHandler: BackendRtpMessageHandler? = null

    override fun init() {
        val plugin = Velocity.requirePlugin()
        val server = Velocity.requireProxyServer()
        val requestManager = RtpRequestManager(server, ProxyRtpConfig())
        val handler =
            BackendRtpMessageHandler { player, world ->
                requestManager.request(player, world)
            }
        server.channelRegistrar.register(
            RtpRequestManager.CHANNEL,
            BackendRtpMessageHandler.CHANNEL,
        )
        server.eventManager.register(plugin, requestManager)
        server.eventManager.register(plugin, handler)
        manager = requestManager
        backendHandler = handler
        Velocity.rtpRequestManager = requestManager
    }

    override fun shutdown() {
        val server = Velocity.proxyServer
        manager?.let {
            it.shutdown()
            server?.eventManager?.unregisterListener(Velocity.plugin, it)
        }
        backendHandler?.let {
            server?.eventManager?.unregisterListener(Velocity.plugin, it)
        }
        server?.channelRegistrar?.unregister(
            RtpRequestManager.CHANNEL,
            BackendRtpMessageHandler.CHANNEL,
        )
        manager = null
        backendHandler = null
        Velocity.rtpRequestManager = null
    }

    override fun reload() {}
}
