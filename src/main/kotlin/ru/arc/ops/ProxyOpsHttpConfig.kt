package ru.arc.ops

import ru.arc.config.Config
import ru.arc.config.ProxyConfigs

class ProxyOpsHttpConfig private constructor(
    val enabled: Boolean,
    val token: String,
    val bindHost: String,
    val bindPort: Int,
    val simulateEnabled: Boolean,
    val logsEnabled: Boolean,
) {
    constructor(config: Config) : this(
        enabled = config.bool("enabled", false),
        token = config.string("token", ""),
        bindHost = config.string("bind-host", "127.0.0.1"),
        bindPort = config.integer("bind-port", 25825),
        simulateEnabled = config.bool("simulate-enabled", true),
        logsEnabled = config.bool("logs-enabled", true),
    )

    companion object {
        private val DISABLED =
            ProxyOpsHttpConfig(
                enabled = false,
                token = "",
                bindHost = "127.0.0.1",
                bindPort = 25825,
                simulateEnabled = false,
                logsEnabled = false,
            )

        @Volatile
        private var instance: ProxyOpsHttpConfig = DISABLED

        fun current(): ProxyOpsHttpConfig = instance

        fun reload() {
            instance = ProxyOpsHttpConfig(ProxyConfigs.module("ops-http.yml"))
        }
    }
}
