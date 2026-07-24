package ru.arc.ai.routing.router

import ru.arc.config.Config

data class RouterConfig(
    val enabled: Boolean,
    val model: String,
    val fallbackModel: String,
    val temperature: Double,
    val maxTokens: Int,
    val maxContextLines: Int,
    val maxRouteHistory: Int,
    val continuationWindowSec: Int,
    val observeFormat: String,
    val timeoutSec: Int,
    val logSkipAtDebug: Boolean,
    val logRouteInfo: Boolean,
    val enabledIntents: Set<RouteIntent>,
    val recentOpenTickets: Int,
    val prefilterEnabled: Boolean = true,
    val maxConsecutiveReplies: Int = 2,
) {
    fun isIntentEnabled(intent: RouteIntent): Boolean {
        if (intent == RouteIntent.SKIP) return true
        return enabledIntents.contains(intent)
    }

    companion object {
        private val DEFAULT_ENABLED_INTENTS =
            setOf(
                RouteIntent.CHAT,
                RouteIntent.BUG,
            )

        private const val DEFAULT_OBSERVE_FORMAT = "[%time% %delta%] %player% » %message%"

        private const val DEFAULT_MODEL = "deepseek/deepseek-v4-flash@preset/deepseek"

        fun from(config: Config): RouterConfig {
            val configuredIntents =
                config
                    .stringList(
                        "routing.enabled-intents",
                        DEFAULT_ENABLED_INTENTS.map { it.wireName() },
                    )
                    .mapNotNull { RouteIntent.fromWire(it) }
                    .toSet()
            val enabledIntents =
                if (configuredIntents.isEmpty()) {
                    DEFAULT_ENABLED_INTENTS
                } else {
                    configuredIntents
                }
            return RouterConfig(
                enabled = config.bool("routing.enabled", true),
                model = config.string("routing.model", DEFAULT_MODEL),
                fallbackModel =
                    config.string(
                        "routing.fallback-model",
                        DEFAULT_MODEL,
                    ),
                temperature = config.real("routing.temperature", 0.0),
                maxTokens = config.integer("routing.max-tokens", 120),
                maxContextLines = config.integer("routing.max-context-lines", 15),
                maxRouteHistory = config.integer("routing.max-route-history", 5),
                continuationWindowSec =
                    config.integer(
                        "chat.continuation-window-sec",
                        config.integer("routing.continuation-window-sec", 90),
                    ),
                observeFormat =
                    config.string(
                        "chat.observe-format",
                        config.string("routing.observe-format", DEFAULT_OBSERVE_FORMAT),
                    ),
                timeoutSec = config.integer("routing.timeout-sec", 15),
                logSkipAtDebug = config.bool("routing.log-level-skip-debug", true),
                logRouteInfo = config.bool("routing.log-route-info", true),
                enabledIntents = enabledIntents,
                recentOpenTickets =
                    config.integer("routing.context.recent-open-tickets", 3).coerceIn(0, 10),
                prefilterEnabled = config.bool("routing.prefilter-enabled", true),
                maxConsecutiveReplies =
                    config.integer("chat.max-consecutive-replies", 2).coerceIn(1, 5),
            )
        }
    }
}
