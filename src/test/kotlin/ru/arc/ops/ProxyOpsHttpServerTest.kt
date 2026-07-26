package ru.arc.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.util.concurrent.Executors

class ProxyOpsHttpServerTest : FreeSpec({
    "stop shuts down the worker executor" {
        val directory = Files.createTempDirectory("proxyarc-ops-server-")
        Files.writeString(
            directory.resolve("ops-http.yml"),
            """
            enabled: true
            token: unit-test-token
            bind-host: 127.0.0.1
            bind-port: 0
            """.trimIndent(),
        )
        val config = ProxyOpsHttpConfig(ConfigManager.of(directory, "ops-http.yml"))
        val executor = Executors.newSingleThreadExecutor()
        val server = ProxyOpsHttpServer({ executor }, { config })
        server.start()

        server.stop()

        executor.isShutdown shouldBe true
    }
})
