package ru.arc.core.modules

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import ru.arc.logging.ArcDiagnosticLogging
import ru.arc.velocity.Velocity
import java.nio.file.Files
import java.nio.file.Paths

class LoggingModuleLifecycleTest : FreeSpec({
    "owns one diagnostic installation through repeated init, reload, disable and shutdown" {
        val previousDataFolder = Velocity.dataFolder
        val context = LogManager.getContext(false) as LoggerContext
        val previousRootLevel = context.configuration.rootLogger.level
        val folder = Files.createTempDirectory("proxy-logging-lifecycle")
        Files.createDirectories(folder.resolve("modules"))
        fun writeConfig(enabled: Boolean) {
            Files.writeString(folder.resolve("modules/logging.yml"), "enabled: false\nlevel: INFO\ndiagnostics-file:\n  enabled: $enabled\n")
        }
        writeConfig(true)
        val lifecycle = mutableListOf<String>()
        var installations = 0
        mockkObject(ArcDiagnosticLogging)
        every { ArcDiagnosticLogging.install(Paths.get("logs")) } answers {
            val id = ++installations
            lifecycle += "install$id"
            AutoCloseable { lifecycle += "close$id" }
        }
        Velocity.dataFolder = folder

        try {
            LoggingModule.init()
            LoggingModule.init()
            LoggingModule.reload()
            writeConfig(false)
            LoggingModule.reload()
            LoggingModule.shutdown()
            writeConfig(true)
            LoggingModule.reload()
            LoggingModule.shutdown()
            LoggingModule.shutdown()

            lifecycle shouldContainExactly listOf(
                "install1", "close1", "install2", "close2", "install3", "close3", "install4", "close4",
            )
        } finally {
            LoggingModule.shutdown()
            Velocity.dataFolder = previousDataFolder
            context.configuration.rootLogger.level = previousRootLevel
            context.updateLoggers()
            unmockkObject(ArcDiagnosticLogging)
            folder.toFile().deleteRecursively()
        }
    }
})
