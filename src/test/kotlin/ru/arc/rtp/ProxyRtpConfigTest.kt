package ru.arc.rtp

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files

class ProxyRtpConfigTest :
    FreeSpec({
        "normalizes routing values and bounds the timeout" {
            val folder = Files.createTempDirectory("proxy-rtp-config-")
            val raw = Config(folder, "rtp.yml")
            raw.setString("target-server", " Survival ")
            raw.setString("default-world", " VANILLA ")
            raw.setString("transfer-message", "  <gray>Переход…  ")
            raw.setStringList("allowed-worlds", listOf("Survival", "mining", "MINING"))
            raw.setLong("request-timeout-seconds", 500L)
            val config = ProxyRtpConfig(raw)

            config.targetServer shouldBe "survival"
            config.defaultWorld shouldBe "vanilla"
            config.transferMessage shouldBe "<gray>Переход…"
            config.allowedWorlds shouldBe listOf("survival", "mining")
            config.requestTimeoutMillis shouldBe 60_000L
        }

        "keeps an empty transfer message disabled" {
            val folder = Files.createTempDirectory("proxy-rtp-config-")
            val raw = Config(folder, "rtp.yml")
            raw.setString("transfer-message", "   ")

            ProxyRtpConfig(raw).transferMessage shouldBe ""
        }
    })
