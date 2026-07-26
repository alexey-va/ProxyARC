package ru.arc.velocity

import com.velocitypowered.api.proxy.ProxyServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import org.slf4j.Logger
import java.nio.file.Files

class VelocityRuntimeStateTest : FreeSpec({
    val dataPath = Files.createTempDirectory("proxyarc-runtime-test")

    afterTest {
        Velocity.plugin = null
        Velocity.proxyServer = null
        Velocity.logger = null
        Velocity.dataFolder = null
    }
    afterSpec {
        dataPath.toFile().deleteRecursively() shouldBe true
    }

    "constructor should install non-null runtime references" {
        val server = mockk<ProxyServer>()
        val logger = mockk<Logger>()
        val plugin = Velocity(server, logger, dataPath)

        Velocity.requirePlugin() shouldBeSameInstanceAs plugin
        Velocity.requireProxyServer() shouldBeSameInstanceAs server
        Velocity.requireDataFolder() shouldBeSameInstanceAs dataPath
    }

    "missing runtime references should fail with explicit state errors" {
        Velocity.plugin = null
        Velocity.proxyServer = null
        Velocity.dataFolder = null

        shouldThrow<IllegalStateException> { Velocity.requirePlugin() }
        shouldThrow<IllegalStateException> { Velocity.requireProxyServer() }
        shouldThrow<IllegalStateException> { Velocity.requireDataFolder() }
    }
})
