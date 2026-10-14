package ru.arc.discord

import com.neovisionaries.ws.client.WebSocketFactory
import net.dv8tion.jda.api.JDABuilder
import okhttp3.OkHttpClient
import ru.arc.config.Config
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal data class DiscordProxySettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
) {
    fun applyTo(builder: JDABuilder, health: DiscordTransportHealth? = null) {
        val http = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        health?.let { http.eventListener(it.httpListener) }
        val webSocketFactory = WebSocketFactory().setConnectionTimeout(CONNECT_TIMEOUT_MS)
        if (enabled) {
            val address = InetSocketAddress(host, port)
            http.proxy(Proxy(Proxy.Type.HTTP, address))
            webSocketFactory.proxySettings.setHost(host).setPort(port)
        }
        builder.setHttpClientBuilder(http)
        builder.setWebsocketFactory(webSocketFactory)
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 8L
        private const val REQUEST_TIMEOUT_SECONDS = 15L
        private const val CONNECT_TIMEOUT_MS = (CONNECT_TIMEOUT_SECONDS * 1_000).toInt()

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
