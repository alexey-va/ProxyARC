package ru.arc.ai.routing.dispatch

import com.velocitypowered.api.proxy.ProxyServer
import ru.arc.ai.routing.history.RouteHistoryStore
import ru.arc.ai.routing.router.RouterConfig
import ru.arc.config.Config

/** Shared dependencies for intent handlers (pipelines). */
data class DispatchServices(
    val proxyServer: ProxyServer,
    val routerConfig: RouterConfig,
    val assistantConfig: Config,
    val routeHistory: RouteHistoryStore,
)
