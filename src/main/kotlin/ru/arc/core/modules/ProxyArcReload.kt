package ru.arc.core.modules

import ru.arc.config.ConfigManager
import ru.arc.config.ProxyConfigs
import ru.arc.ai.AssistantModule
import ru.arc.core.ModuleRegistry
import ru.arc.core.PluginModule
import ru.arc.redis.RedisModuleConfig
import ru.arc.ops.ProxyOpsHttpModule
import ru.arc.channelsync.ChannelSyncModule
import ru.arc.telegram.TelegramModule
import ru.arc.velocity.Velocity

/**
 * Safe hot-reload for configuration-backed modules that own replaceable runtime state.
 * Discord/JDA and Redis keep their process-bound connections until the next Velocity restart.
 */
object ProxyArcReload {
    private val modules: List<PluginModule> =
        listOf(
            LoggingModule,
            NetworkModule,
            MetricsModule,
            JoinMessageCatalogModule,
            TelegramModule,
            ChannelSyncModule,
            AssistantModule,
            ProxyOpsHttpModule,
        )

    internal fun moduleNames(): List<String> = modules.map(PluginModule::name)

    fun reloadSupported(): ProxyArcReloadResult {
        ConfigManager.reloadAll()
        Velocity.config = ProxyConfigs.main()
        Velocity.dataFolder?.let { dataFolder ->
            Velocity.serverName = RedisModuleConfig.load(dataFolder).serverName
        }

        val reloaded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        modules.forEach { module ->
            if (ModuleRegistry.reload(module)) {
                reloaded += module.name
            } else {
                failed += module.name
            }
        }
        return ProxyArcReloadResult(reloaded = reloaded, failed = failed)
    }
}

data class ProxyArcReloadResult(
    val reloaded: List<String>,
    val failed: List<String>,
)
