package ru.arc.discord

import com.neovisionaries.ws.client.WebSocketFactory
import net.dv8tion.jda.api.JDABuilder
import okhttp3.OkHttpClient
import ru.arc.config.Config
import java.net.InetSocketAddress
import java.net.Proxy

internal data class DiscordProxySettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
) {
    fun applyTo(builder: JDABuilder) {
        if (!enabled) return

        val address = InetSocketAddress(host, port)
        builder.setHttpClientBuilder(
            OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, address)),
        )

        val webSocketFactory = WebSocketFactory()
        webSocketFactory.proxySettings
            .setHost(host)
            .setPort(port)
        builder.setWebsocketFactory(webSocketFactory)
    }

    companion object {
        fun from(config: Config): DiscordProxySettings {
            val enabled = config.bool("http-proxy.enabled", false)
            val host = config.string("http-proxy.host", "").trim()
            val port = config.integer("http-proxy.port", 8888)
            if (enabled) {
                require(host.isNotBlank()) { "http-proxy.host is required when Discord proxy is enabled" }
                require(port in 1..65535) { "http-proxy.port must be 1..65535" }
            }
            return DiscordProxySettings(enabled, host, port)
        }
    }
}
