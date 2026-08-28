package ru.arc.telegram

import org.telegram.telegrambots.bots.DefaultBotOptions
import ru.arc.config.Config

/** Applies the shared Velocity outbound HTTP proxy to both polling and Bot API calls. */
internal data class TelegramProxySettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
) {
    fun botOptions(): DefaultBotOptions =
        DefaultBotOptions().also { options ->
            if (enabled) {
                options.proxyType = DefaultBotOptions.ProxyType.HTTP
                options.proxyHost = host
                options.proxyPort = port
            }
        }

    companion object {
        fun from(config: Config): TelegramProxySettings {
            val enabled = config.bool("http-proxy.enabled", false)
            val host = config.string("http-proxy.host", "").trim()
            val port = config.integer("http-proxy.port", 8888)
            if (enabled) {
                require(host.isNotBlank()) { "http-proxy.host is required when Telegram proxy is enabled" }
                require(port in 1..65_535) { "http-proxy.port must be 1..65535" }
            }
            return TelegramProxySettings(enabled, host, port)
        }
    }
}
