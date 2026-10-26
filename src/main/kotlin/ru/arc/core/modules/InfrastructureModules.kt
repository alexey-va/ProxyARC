package ru.arc.core.modules

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.Configuration
import org.apache.logging.log4j.core.config.LoggerConfig
import org.slf4j.LoggerFactory
import ru.arc.config.ConfigManager
import ru.arc.config.ProxyConfigs
import ru.arc.core.PluginModule
import ru.arc.logging.ArcDiagnosticLogging
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.LoggingModuleConfig
import ru.arc.logging.LokiAttachTarget
import ru.arc.logging.LokiInstallSpec
import ru.arc.logging.Slf4jLoggingPlatform
import ru.arc.velocity.Velocity
import ru.arc.xserver.NetworkRegistry
import ru.arc.redis.RedisConfigBootstrap
import ru.arc.redis.RedisModuleConfig
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import java.nio.file.Paths

private val log = LoggerFactory.getLogger("ru.arc.core.modules.Infrastructure")

internal fun transportFailureFilterLoggerConfigs(configuration: Configuration): Set<LoggerConfig> {
    fun propagationPath(start: LoggerConfig): List<LoggerConfig> {
        val path = mutableListOf<LoggerConfig>()
        var current: LoggerConfig? = start
        while (current != null) {
            path += current
            if (!current.isAdditive) break
            current = current.parent
        }
        return path
    }

    val paths =
        listOf(
            configuration.getLoggerConfig(TransportFailureLogFilter.JDA_LOGGER_NAME),
            configuration.getLoggerConfig(TransportFailureLogFilter.TELEGRAM_LOGGER_NAME),
        ).map(::propagationPath)
    val commonConfigs = paths.first().filter { candidate -> paths.drop(1).all { candidate in it } }
    if (commonConfigs.isNotEmpty()) {
        val deepestCommonConfig = commonConfigs.minByOrNull { candidate -> paths.maxOf { it.indexOf(candidate) } }
        if (deepestCommonConfig != null) return setOf(deepestCommonConfig)
    }

    // Non-additive routes do not meet. Attach once at each route's effective LoggerConfig.
    return paths.map { it.first() }.toSet()
}

// ==================== Priority 10-29: Core Infrastructure ====================

object LoggingModule : PluginModule {
    override val name = "Logging"
    override val priority = 10

    @Volatile private var transportFailureFilter: TransportFailureLogFilter? = null
    private var transportFilterContext: LoggerContext? = null
    private var transportFilterConfiguration: Configuration? = null
    private var transportFilterLoggerConfigs: Set<LoggerConfig> = emptySet()
    private var diagnosticFileLogging: AutoCloseable? = null

    override fun init() {
        val folder = Velocity.dataFolder ?: return
        configureDiagnosticFileLogging()
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
            applyConfiguredRootLevel()
            installTransportFailureFilter()
        } catch (e: Exception) {
            log.error("Error adding loki appender", e)
        }
    }

    override fun shutdown() {
        closeDiagnosticFileLogging()
        removeTransportFailureFilter()
    }

    override fun reload() {
        ConfigManager.reloadAll()
        val folder = Velocity.dataFolder ?: return
        configureDiagnosticFileLogging()
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
            applyConfiguredRootLevel()
            installTransportFailureFilter()
        } catch (e: Exception) {
            log.error("Error reloading Loki appender", e)
        }
    }

    internal fun transportFailureWasLogged(loggerName: String): Boolean =
        transportFailureFilter?.transportFailureWasLogged(loggerName) ?: false

    internal fun transportRecovered(loggerName: String): Int =
        transportFailureFilter?.transportRecovered(loggerName) ?: 0

    @Synchronized
    private fun configureDiagnosticFileLogging() {
        closeDiagnosticFileLogging()
        try {
            if (ProxyConfigs.module("logging.yml").bool("diagnostics-file.enabled", true)) {
                diagnosticFileLogging = ArcDiagnosticLogging.install(Paths.get("logs"))
            }
        } catch (e: Exception) {
            log.error("Error configuring diagnostic file logging", e)
        }
    }

    @Synchronized
    private fun closeDiagnosticFileLogging() {
        val installed = diagnosticFileLogging
        diagnosticFileLogging = null
        try {
            installed?.close()
        } catch (e: Exception) {
            log.error("Error closing diagnostic file logging", e)
        }
    }

    @Synchronized
    private fun installTransportFailureFilter() {
        val context = LogManager.getContext(false) as LoggerContext
        val configuration = context.configuration
        val loggerConfigs = transportFailureFilterLoggerConfigs(configuration)
        val filter =
            transportFailureFilter
                ?: TransportFailureLogFilter().also {
                    it.start()
                    transportFailureFilter = it
                }

        if (
            transportFilterConfiguration === configuration &&
                transportFilterContext === context &&
                transportFilterLoggerConfigs == loggerConfigs
        ) {
            return
        }

        transportFilterLoggerConfigs.forEach { it.removeFilter(filter) }
        transportFilterContext?.updateLoggers()
        loggerConfigs.forEach { it.addFilter(filter) }
        context.updateLoggers()
        transportFilterContext = context
        transportFilterConfiguration = configuration
        transportFilterLoggerConfigs = loggerConfigs
    }

    @Synchronized
    private fun removeTransportFailureFilter() {
        val filter = transportFailureFilter ?: return
        transportFilterLoggerConfigs.forEach { it.removeFilter(filter) }
        transportFilterContext?.updateLoggers()
        filter.stop()
        transportFailureFilter = null
        transportFilterContext = null
        transportFilterConfiguration = null
        transportFilterLoggerConfigs = emptySet()
    }

    private fun applyConfiguredRootLevel() {
        val context = LogManager.getContext(false) as LoggerContext
        val level =
            org.apache.logging.log4j.Level.valueOf(
                LoggingModuleConfig(ProxyConfigs.module("logging.yml")).level.name,
            )
        context.configuration.rootLogger.level = level
        context.updateLoggers()
        log.info("Velocity root log level set to {}", level)
    }
}

object ConfigModule : PluginModule {
    override val name = "Config"
    override val priority = 15

    override fun init() {
        val folder = Velocity.requireDataFolder()
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
            Velocity.redisManager?.close()
            Velocity.redisManager = null
            log.info("Redis disabled — skipping connection (redis.enabled=false)")
            return
        }

        val connection = redis.connection()

        val current = Velocity.redisManager
        if (current != null) {
            current.connect(connection)
        } else {
            Velocity.redisManager =
                RedisManager(
                    connection,
                    ServerIdentity { Velocity.serverName },
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
        Velocity.networkRegistry?.close()
        val registry = NetworkRegistry(redis)
        registry.init()
        Velocity.networkRegistry = registry
    }

    override fun shutdown() {
        Velocity.networkRegistry?.close()
        Velocity.networkRegistry = null
    }

    override fun reload() = init()
}
