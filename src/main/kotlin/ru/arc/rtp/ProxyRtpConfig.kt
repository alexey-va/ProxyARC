package ru.arc.rtp

import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import java.util.Locale

class ProxyRtpConfig(
    private val config: Config = ProxyConfigs.module("rtp.yml"),
) {
    val enabled: Boolean
        get() = config.bool("enabled", true)

    val targetServer: String
        get() = normalize(config.string("target-server", "survival"))

    val defaultWorld: String
        get() = normalize(config.string("default-world", "survival"))

    val allowedWorlds: List<String>
        get() =
            config
                .stringList("allowed-worlds", listOf("survival", "mining", "vanilla"))
                .map(::normalize)
                .distinct()

    val requestTimeoutMillis: Long
        get() = config.long("request-timeout-seconds", 20L).coerceIn(5L, 60L) * 1000L

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
}
