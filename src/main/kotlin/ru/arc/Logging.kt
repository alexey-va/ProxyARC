package ru.arc

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.layout.PatternLayout
import org.slf4j.LoggerFactory
import pl.tkowalcz.tjahzi.log4j2.Header
import pl.tkowalcz.tjahzi.log4j2.LokiAppender
import pl.tkowalcz.tjahzi.log4j2.labels.Label
import ru.arc.config.ConfigManager
import java.nio.charset.StandardCharsets
import java.nio.file.Path

object Logging {
    private val log = LoggerFactory.getLogger(Logging::class.java)

    @JvmStatic
    fun addLokiAppender(folder: Path) {
        try {
            val config = ConfigManager.of(folder, "logging.yml")
            val labels = config.map<String>("labels", emptyMap()).entries
                .filter { (key, value) ->
                    if (!Label.hasValidName(key)) {
                        log.warn("Invalid label: {}", mapOf(key to value))
                        return@filter false
                    }
                    if (value !is String) {
                        log.warn("Null value for label: {}", mapOf(key to value))
                        return@filter false
                    }
                    true
                }
                .map { (key, value) -> Label.createLabel(key, value, null) }
                .toTypedArray()
            val headers = Array(labels.size) { i ->
                val label = labels[i]
                Header.createHeader(label.name, label.value)
            }

            val layout = PatternLayout.newBuilder()
                .withPattern(
                    "{\"instant\":{\"epochSecond\":%d{UNIX},\"nanoOfSecond\":%nano},"
                        + "\"thread\":\"%t\","
                        + "\"level\":\"%p\","
                        + "\"loggerName\":\"%c\","
                        + "\"message\":\"%enc{%m}{JSON}\","
                        + "\"endOfBatch\":false,"
                        + "\"loggerFqcn\":\"%fqcn\","
                        + "\"threadId\":%tid,"
                        + "\"threadPriority\":%threadPriority}%n",
                )
                .withCharset(StandardCharsets.UTF_8)
                .build()

            val builder = LokiAppender.newBuilder()
            builder.setHost(config.string("host"))
            builder.setPort(config.integer("port", 3100))
            builder.setLabels(labels)
            builder.setHeaders(headers)
            builder.setName("lokiAppender")
            builder.setLayout(layout)
            val build = builder.build()
            build.start()

            val rootLogger = LogManager.getRootLogger() as Logger
            val configuration = rootLogger.context.configuration
            configuration.addAppender(build)
            val loggerConfig = configuration.getLoggerConfig(LogManager.ROOT_LOGGER_NAME)
            loggerConfig.addAppender(build, Level.INFO, null)
            rootLogger.context.updateLoggers()
        } catch (e: Exception) {
            log.warn("Failed to add lokiAppender", e)
        }
    }
}
