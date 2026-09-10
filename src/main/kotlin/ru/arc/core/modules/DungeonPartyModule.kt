package ru.arc.core.modules

import ru.arc.core.PluginModule
import ru.arc.dungeonparty.DungeonPartyGatherManager
import ru.arc.velocity.Velocity

object DungeonPartyModule : PluginModule {
    override val name = "DungeonParty"
    override val priority = 56

    private var manager: DungeonPartyGatherManager? = null

    override fun init() {
        val plugin = Velocity.requirePlugin()
        val server = Velocity.requireProxyServer()
        val created = DungeonPartyGatherManager(server)
        server.channelRegistrar.register(
            DungeonPartyGatherManager.REQUEST_CHANNEL,
            DungeonPartyGatherManager.ARRIVAL_CHANNEL,
        )
        server.eventManager.register(plugin, created)
        manager = created
    }

    override fun shutdown() {
        val server = Velocity.proxyServer
        manager?.let {
            it.shutdown()
            server?.eventManager?.unregisterListener(Velocity.plugin, it)
        }
        server?.channelRegistrar?.unregister(
            DungeonPartyGatherManager.REQUEST_CHANNEL,
            DungeonPartyGatherManager.ARRIVAL_CHANNEL,
        )
        manager = null
    }

    override fun reload() = Unit
}
