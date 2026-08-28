package ru.arc.core.modules

import ru.arc.config.ConfigManager
import ru.arc.config.ProxyConfigs
import ru.arc.ai.AssistantModule
import ru.arc.core.ModuleRegistry
import ru.arc.ops.ProxyOpsHttpModule
import ru.arc.channelsync.ChannelSyncModule
import ru.arc.telegram.TelegramModule
import ru.arc.velocity.Velocity

/**
 * Safe hot-reload for config-backed modules.
 * Does not restart Discord/JDA, Redis, or SaveModule executor.
 */
object ProxyArcReload {
    fun configsAndAssistant() {
        Velocity.firstJoinData?.save()
        ConfigManager.reloadAll()
        Velocity.config = ProxyConfigs.main()
        ModuleRegistry.reload(MetricsModule)
        ModuleRegistry.reload(AssistantModule)
        ModuleRegistry.reload(TelegramModule)
        ModuleRegistry.reload(ChannelSyncModule)
        ModuleRegistry.reload(ProxyOpsHttpModule)
    }
}
