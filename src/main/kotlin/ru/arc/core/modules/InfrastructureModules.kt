package ru.arc.core.modules

import org.slf4j.LoggerFactory
import ru.arc.config.ConfigManager
import ru.arc.config.ProxyConfigs
import ru.arc.core.PluginModule
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.LokiAttachTarget
import ru.arc.logging.LokiInstallSpec
import ru.arc.logging.Slf4jLoggingPlatform
import ru.arc.velocity.Velocity
import ru.arc.xserver.NetworkRegistry
import ru.arc.redis.RedisConfigBootstrap
import ru.arc.redis.RedisModuleConfig
import ru.arc.xserver.RedisManager
import ru.arc.xserver.ServerIdentity

private val log = LoggerFactory.getLogger("ru.arc.core.modules.Infrastructure")

// ==================== Priority 10-29: Core Infrastructure ====================

object LoggingModule : PluginModule {
    override val name = "Logging"
    override val priority = 10

    override fun init() {
        val folder = Velocity.dataFolder ?: return
        try {
            val configSource =
                object : LoggingConfigSource {
                    override fun config() = ProxyConfigs.module("logging.yml")

                    override fun configVersion(): Int = ConfigManager.getVersion()
                }
            ArcLogging.install(
                platform = Slf4jLoggingPlatform("ProxyARC"),
                configSource = configSource,
                loki =
                    LokiInstallSpec(
                        dataFolder = folder,
                        target = LokiAttachTarget.ROOT,
                        appenderName = "lokiAppender",
                    ),
            )
        } catch (e: Exception) {
            log.error("Error adding loki appender", e)
        }
    }

    override fun shutdown() {}

    override fun reload() {
        ConfigManager.reloadAll()
        val folder = Velocity.dataFolder ?: return
        try {
            val configSource =
                object : LoggingConfigSource {
                    override fun config() = ProxyConfigs.module("logging.yml")

                    override fun configVersion(): Int = ConfigManager.getVersion()
                }
            ArcLogging.install(
                platform = Slf4jLoggingPlatform("ProxyARC"),
                configSource = configSource,
                loki =
                    LokiInstallSpec(
                        dataFolder = folder,
                        target = LokiAttachTarget.ROOT,
                        appenderName = "lokiAppender",
                    ),
            )
        } catch (e: Exception) {
            log.error("Error reloading Loki appender", e)
        }
    }
}

object ConfigModule : PluginModule {
    override val name = "Config"
    override val priority = 15

    override fun init() {
        val folder = Velocity.dataFolder!!
        RedisConfigBootstrap.ensure(folder)
        Velocity.config = ProxyConfigs.main()
        Velocity.serverName = RedisModuleConfig.load(folder).serverName
    }

    override fun reload() {
        ConfigManager.reloadAll()
        init()
    }

    override fun shutdown() {}
}

object RedisModule : PluginModule {
    override val name = "Redis"
    override val priority = 20

    override fun init() {
        val folder = Velocity.dataFolder ?: return
        RedisConfigBootstrap.ensure(folder)
        val redis = RedisModuleConfig.load(folder)

        if (!redis.enabled) {
            log.info("Redis disabled — skipping connection (redis.enabled=false)")
            return
        }

        val connection = redis.connection()

        if (Velocity.redisManager != null) {
            Velocity.redisManager!!.connect(connection)
        } else {
            Velocity.redisManager =
                RedisManager(
                    connection,
                    ServerIdentity { Velocity.serverName ?: redis.serverName },
                )
        }
    }

    override fun reload() = init()

    override fun shutdown() {
        Velocity.redisManager?.close()
        Velocity.redisManager = null
    }
}

object NetworkModule : PluginModule {
    override val name = "Network"
    override val priority = 25

    override fun init() {
        val redis = Velocity.redisManager ?: return
        Velocity.networkRegistry = NetworkRegistry(redis)
        Velocity.networkRegistry!!.init()
    }

    override fun shutdown() {
        Velocity.networkRegistry = null
    }

    override fun reload() {}
}
