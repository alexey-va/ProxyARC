package ru.arc.ai.routing.live

import ru.arc.ai.config.LlmModuleConfig
import ru.arc.config.EmptyConfig

/**
 * Live integration tests read credentials from env (never commit keys).
 *
 * OPENROUTER_API_KEY — required
 * OPENROUTER_PROXY_ENABLED — default false
 * OPENROUTER_PROXY_HOST / OPENROUTER_PROXY_PORT — if proxy enabled
 * ROUTER_MODEL — default deepseek/deepseek-v4-flash
 */
object LiveTestLlmConfig : LlmModuleConfig(EmptyConfig) {
    override val llmEnabled: Boolean
        get() = apiKey != "none"

    override val apiKey: String
        get() =
            System.getenv("OPENROUTER_API_KEY")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "none"

    override val proxyEnabled: Boolean
        get() = System.getenv("OPENROUTER_PROXY_ENABLED")?.toBooleanStrictOrNull() ?: false

    override val proxyHost: String
        get() = System.getenv("OPENROUTER_PROXY_HOST")?.trim().orEmpty().ifBlank { "185.242.106.81" }

    override val proxyPort: Int
        get() = System.getenv("OPENROUTER_PROXY_PORT")?.toIntOrNull() ?: 8888

    override val timeoutSeconds: Long
        get() = System.getenv("OPENROUTER_TIMEOUT_SEC")?.toLongOrNull() ?: 45L
}
