package ru.arc.core.modules

import org.slf4j.LoggerFactory
import ru.arc.Logging
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity
import ru.arc.xserver.NetworkRegistry
import ru.arc.xserver.RedisManager

private val log = LoggerFactory.getLogger("ru.arc.core.modules.Infrastructure")

// ==================== Priority 10-29: Core Infrastructure ====================

object LoggingModule : PluginModule {
    override val name = "Logging"
    override val priority = 10

    override fun init() {
        val folder = Velocity.dataFolder ?: return
        try {
            Logging.addLokiAppender(folder)
        } catch (e: Exception) {
            log.error("Error adding loki appender", e)
        }
    }

    override fun shutdown() {}

    override fun reload() {}
}

object ConfigModule : PluginModule {
    override val name = "Config"
    override val priority = 15

    override fun init() {
        val folder = Velocity.dataFolder!!
        Velocity.config = ConfigManager.of(folder, "config.yml")
        Velocity.serverName = Velocity.config!!.string("server-name", "proxy")
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
        val config = Velocity.config ?: ConfigManager.of(Velocity.dataFolder!!, "config.yml")
        val host = config.string("redis.host", "localhost")
        val port = config.integer("redis.port", 6379)
        val username = config.string("redis.username", "default")
        val password = config.string("redis.password", "")

        if (Velocity.redisManager != null) {
            Velocity.redisManager!!.connect(host, port, username, password)
        } else {
            Velocity.redisManager = RedisManager(host, port, username, password)
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
}
