package ru.arc.velocity

import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.slf4j.Logger
import ru.arc.core.ModuleRegistry
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.core.VelocityArcRuntime
import java.nio.file.Files

class VelocityRuntimeStateTest : FreeSpec({
    val dataPath = Files.createTempDirectory("proxyarc-runtime-test")

    afterTest {
        Velocity.plugin = null
        Velocity.proxyServer = null
        Velocity.logger = null
        Velocity.dataFolder = null
        Velocity.proxyRestartService = null
    }
    afterSpec {
        dataPath.toFile().deleteRecursively() shouldBe true
    }

    "constructor remains passive until the proxy initialization event" {
        Velocity.plugin = null
        Velocity.proxyServer = null
        Velocity.dataFolder = null
        val server = mockk<ProxyServer>()
        val logger = mockk<Logger>()
        Velocity(server, logger, dataPath)

        Velocity.plugin shouldBe null
        Velocity.proxyServer shouldBe null
        Velocity.dataFolder shouldBe null
    }

    "missing runtime references should fail with explicit state errors" {
        Velocity.plugin = null
        Velocity.proxyServer = null
        Velocity.dataFolder = null

        shouldThrow<IllegalStateException> { Velocity.requirePlugin() }
        shouldThrow<IllegalStateException> { Velocity.requireProxyServer() }
        shouldThrow<IllegalStateException> { Velocity.requireDataFolder() }
    }

    "shutdown preserves a replaced command and retires all owners even when a module fails" {
        val server = mockk<ProxyServer>(relaxed = true)
        val logger = mockk<Logger>(relaxed = true)
        val plugin = Velocity(server, logger, dataPath)
        val commands = mutableMapOf<String, CommandMeta>()
        every { server.commandManager.metaBuilder(any<String>()) } answers {
            val alias = firstArg<String>()
            val metadata = mockk<CommandMeta> { every { aliases } returns listOf(alias) }
            mockk<CommandMeta.Builder> {
                every { plugin(any()) } returns this
                every { build() } returns metadata
            }
        }
        every { server.commandManager.register(any<CommandMeta>(), any()) } answers {
            val metadata = firstArg<CommandMeta>()
            metadata.aliases.forEach { commands[it] = metadata }
        }
        every { server.commandManager.getCommandMeta(any()) } answers { commands[firstArg()] }
        every { server.commandManager.unregister(any<String>()) } answers { commands.remove(firstArg()); Unit }
        mockkObject(ModuleRegistry)
        mockkStatic(VelocityArcRuntime::class)
        every { ModuleRegistry.registerAll(*anyVararg()) } just Runs
        every { ModuleRegistry.initAll() } just Runs
        every { ModuleRegistry.shutdownAllChecked() } throws IllegalStateException("injected module shutdown failure")
        every { VelocityArcRuntime.installScheduling(server, plugin) } answers { Tasks.install(TestTaskScheduler()) }
        every { VelocityArcRuntime.installModuleLifecycleReporting(any(), any()) } just Runs
        try {
            plugin.onProxyInit(ProxyInitializeEvent())
            commands.keys shouldBe setOf("proxyarc", "verify", "discord", "telegram")
            val replacement = mockk<CommandMeta>()
            commands["verify"] = replacement
            plugin.onProxyStop(ProxyShutdownEvent())
            plugin.onProxyStop(ProxyShutdownEvent())
            commands shouldBe mapOf("verify" to replacement)
            verify(exactly = 1) { server.eventManager.unregisterListeners(plugin); ModuleRegistry.shutdownAllChecked() }
            Velocity.plugin shouldBe null
            Velocity.proxyServer shouldBe null
            shouldThrow<IllegalStateException> { Tasks.scheduler }
            every { ModuleRegistry.initAll() } throws IllegalStateException("injected startup failure")
            shouldThrow<IllegalStateException> { plugin.onProxyInit(ProxyInitializeEvent()) }.message shouldBe "injected startup failure"
            verify(exactly = 2) { server.eventManager.unregisterListeners(plugin); ModuleRegistry.shutdownAllChecked() }
            Velocity.plugin shouldBe null
            shouldThrow<IllegalStateException> { Tasks.scheduler }
            commands.clear()
            every { ModuleRegistry.initAll() } just Runs
            every { server.commandManager.register(any<CommandMeta>(), any()) } answers {
                val metadata = firstArg<CommandMeta>()
                metadata.aliases.forEach { commands[it] = metadata }
                if ("discord" in metadata.aliases) error("injected partial registration failure")
            }
            shouldThrow<IllegalStateException> { plugin.onProxyInit(ProxyInitializeEvent()) }.message shouldBe "injected partial registration failure"
            commands shouldBe emptyMap()
            verify(exactly = 3) { server.eventManager.unregisterListeners(plugin); ModuleRegistry.shutdownAllChecked() }
        } finally {
            Tasks.reset()
            unmockkObject(ModuleRegistry)
            unmockkStatic(VelocityArcRuntime::class)
        }
    }
})
