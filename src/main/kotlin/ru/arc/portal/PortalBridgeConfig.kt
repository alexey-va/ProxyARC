package ru.arc.portal

import ru.arc.config.Config
import ru.arc.config.EmptyConfig
import ru.arc.config.ProxyConfigs
import java.net.URI

open class PortalBridgeConfig(
    private val config: Config,
) {
    open val enabled: Boolean
        get() = config.bool("enabled", false)

    open val baseUrl: String
        get() = config.string("base-url", "https://rus-crafting.ru").trim().removeSuffix("/")

    open val bridgeToken: String
        get() = System.getenv("PORTAL_BRIDGE_TOKEN")?.trim().orEmpty()
            .ifBlank { config.string("bridge-token", "").trim() }

    open val presenceIntervalTicks: Long
        get() = config.durationTicks("presence-interval", 1_200L).coerceIn(200L, 6_000L)

    open val identityIntervalTicks: Long
        get() = config.durationTicks("identity-interval", 600L).coerceIn(200L, 12_000L)

    open val connectTimeoutMillis: Long
        get() = config.durationMillis("connect-timeout", 5_000L).coerceIn(1_000L, 30_000L)

    open val requestTimeoutMillis: Long
        get() = config.durationMillis("request-timeout", 8_000L).coerceIn(1_000L, 30_000L)

    open val maxInFlight: Int
        get() = config.integer("max-in-flight", 32).coerceIn(1, 128)

    fun validate() {
        if (!enabled) return
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        require(uri != null && uri.scheme in setOf("http", "https") && uri.host != null) {
            "portal bridge base-url must be an absolute http(s) URL"
        }
        require(uri.query == null && uri.fragment == null && uri.userInfo == null) {
            "portal bridge base-url must not contain credentials, query or fragment"
        }
        require(bridgeToken.length >= 32) { "portal bridge token must contain at least 32 characters" }
    }

    companion object {
        fun load(): PortalBridgeConfig = PortalBridgeConfig(ProxyConfigs.module("portal-bridge.yml"))
    }
}

internal class TestPortalBridgeConfig(
    override val enabled: Boolean = true,
    override val baseUrl: String = "https://portal.example",
    override val bridgeToken: String = "test-bridge-token-that-is-long-enough",
    override val presenceIntervalTicks: Long = 1_200L,
    override val identityIntervalTicks: Long = 600L,
    override val connectTimeoutMillis: Long = 5_000L,
    override val requestTimeoutMillis: Long = 8_000L,
    override val maxInFlight: Int = 32,
) : PortalBridgeConfig(EmptyConfig)
