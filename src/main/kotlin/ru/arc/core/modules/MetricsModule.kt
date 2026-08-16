package ru.arc.core.modules

import ru.arc.config.ProxyConfigs
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.metrics.core.ArcMetricsRuntime
import ru.arc.metrics.core.MetricPoint
import ru.arc.metrics.core.MetricsConfig
import ru.arc.metrics.core.MetricsIdentity
import ru.arc.metrics.core.RedisMetricsBinder
import ru.arc.metrics.velocity.VelocityMetricsCollector
import ru.arc.metrics.ProxyProductConfig
import ru.arc.metrics.ProxyProductTelemetry
import ru.arc.metrics.ProxyProductTelemetryListener
import ru.arc.velocity.Velocity
import kotlin.time.Duration.Companion.seconds

/** Velocity lifecycle adapter around the shared cached Prometheus runtime. */
object MetricsModule : PluginModule {
    override val name = "Metrics"
    override val priority = 27

    private var runtime: ArcMetricsRuntime? = null
    private var collector: VelocityMetricsCollector? = null
    private var redisMetrics: RedisMetricsBinder? = null
    private var productTelemetry: ProxyProductTelemetry? = null
    private var productListener: ProxyProductTelemetryListener? = null
    private var sampleTask: ScheduledTask? = null

    override fun init() {
        shutdown()
        val cfg = MetricsConfig(ProxyConfigs.module("metrics.yml"))
        if (!cfg.enabled) return

        val proxy = Velocity.requireProxyServer()
        val plugin = Velocity.requirePlugin()
        val metrics =
            ArcMetricsRuntime(
                config = cfg,
                identity =
                    MetricsIdentity(
                        application = "ProxyARC",
                        platform = "velocity",
                        serverName = Velocity.serverName,
                        version = proxy.version.version,
                    ),
                dataPath = Velocity.requireDataFolder(),
            )
        val velocity = VelocityMetricsCollector(proxy, plugin, metrics.registry)
        val redisBinder = Velocity.redisManager?.let { RedisMetricsBinder(it, metrics.registry) }
        val productConfig = ProxyProductConfig.from(ProxyConfigs.module("metrics.yml"))
        val product =
            if (productConfig.enabled) {
                ProxyProductTelemetry(
                    registry = metrics.registry,
                    config = productConfig,
                    redis = Velocity.redisManager,
                    rawSource = Velocity.serverName,
                )
            } else {
                null
            }
        val listener = product?.let { ProxyProductTelemetryListener(proxy, it) }
        try {
            metrics.start()
            velocity.start()
            listener?.let { proxy.eventManager.register(plugin, it) }
            runtime = metrics
            collector = velocity
            redisMetrics = redisBinder
            productTelemetry = product
            productListener = listener
            sample()
            sampleTask =
                repeating(
                    cfg.sampleIntervalSeconds.seconds,
                    delay = cfg.sampleIntervalSeconds.seconds,
                ) {
                    sample()
                }
        } catch (failure: Throwable) {
            listener?.let { proxy.eventManager.unregisterListener(plugin, it) }
            redisBinder?.close()
            velocity.stop()
            metrics.close()
            throw failure
        }
    }

    private fun sample() {
        val metrics = runtime ?: return
        val velocity = collector ?: return
        metrics.recordSnapshot("velocity", "platform") {
            val redis = Velocity.redisManager
            velocity.fastSnapshot() +
                MetricPoint(
                    "arc_redis_connected",
                    "ProxyARC Redis connection state",
                    if (redis?.isConnected() == true) 1.0 else 0.0,
                ) +
                MetricPoint(
                    "arc_redis_subscription_active",
                    "ProxyARC Redis subscription state",
                    if (redis?.isSubscriptionActive() == true) 1.0 else 0.0,
                ) +
                MetricPoint(
                    "arc_redis_channels",
                    "Registered ProxyARC Redis channels",
                    (redis?.getChannelCount() ?: 0).toDouble(),
                ) +
                (productTelemetry?.snapshot(redis?.isConnected() == true) ?: emptyList())
        }
    }

    override fun reload() = init()

    override fun shutdown() {
        sampleTask?.cancel()
        sampleTask = null
        collector?.stop()
        collector = null
        val listener = productListener
        val proxy = Velocity.proxyServer
        val plugin = Velocity.plugin
        if (listener != null && proxy != null && plugin != null) {
            proxy.eventManager.unregisterListener(plugin, listener)
        }
        productListener = null
        productTelemetry = null
        redisMetrics?.close()
        redisMetrics = null
        runtime?.close()
        runtime = null
    }
}
