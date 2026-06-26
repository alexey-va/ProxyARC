package ru.arc

import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity

// ==================== Priority 80: Antibot ====================

object AntibotModule : PluginModule {
    override val name = "Antibot"
    override val priority = 80

    override fun init() {
        val folder = Velocity.dataFolder!!
        val firstJoin = Velocity.firstJoinData ?: return
        Velocity.antibot = Antibot(folder, firstJoin)
    }

    override fun shutdown() {
        Velocity.antibot = null
    }

    override fun reload() {
        if (Velocity.antibot == null) init()
    }
}
