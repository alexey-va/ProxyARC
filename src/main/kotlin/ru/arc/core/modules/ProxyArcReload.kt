package ru.arc.core.modules

import ru.arc.config.ConfigManager
import ru.arc.config.ProxyConfigs
import ru.arc.velocity.Velocity

/**
 * Safe hot-reload for configs + assistant prompts.
 * Does not restart Discord/JDA, Redis, listeners, or SaveModule executor.
 */
object ProxyArcReload {
    fun configsAndAssistant() {
        Velocity.firstJoinData?.save()
        ConfigManager.reloadAll()
        Velocity.config = ProxyConfigs.main()
        Velocity.chatAssistant?.reload()
    }
}
