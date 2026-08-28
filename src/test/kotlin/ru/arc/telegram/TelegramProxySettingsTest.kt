package ru.arc.telegram

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.telegram.telegrambots.bots.DefaultBotOptions
import ru.arc.config.ConfigManager
import java.nio.file.Files

class TelegramProxySettingsTest : FreeSpec({
    "configures Telegram polling and API calls through the shared HTTP proxy" {
        val root = Files.createTempDirectory("telegram-proxy-config-")
        val config =
            ConfigManager.of(root, "llm-network.yml").also { value ->
                value.setBoolean("http-proxy.enabled", true)
                value.setString("http-proxy.host", "172.29.172.3")
                value.setInt("http-proxy.port", 8_888)
                value.saveStrict()
            }

        val settings = TelegramProxySettings.from(config)
        val options = settings.botOptions()

        settings.enabled shouldBe true
        options.proxyType shouldBe DefaultBotOptions.ProxyType.HTTP
        options.proxyHost shouldBe "172.29.172.3"
        options.proxyPort shouldBe 8_888
    }

    "rejects an incomplete enabled proxy" {
        val root = Files.createTempDirectory("telegram-invalid-proxy-")
        val config =
            ConfigManager.of(root, "llm-network.yml").also { value ->
                value.setBoolean("http-proxy.enabled", true)
                value.saveStrict()
            }

        shouldThrow<IllegalArgumentException> {
            TelegramProxySettings.from(config)
        }
    }
})
