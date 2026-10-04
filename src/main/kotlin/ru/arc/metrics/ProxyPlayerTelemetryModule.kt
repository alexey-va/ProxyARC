package ru.arc.metrics

import ru.arc.config.ProxyConfigs
import ru.arc.core.PluginModule
import ru.arc.sql.SqlModuleConfig
import ru.arc.telemetry.PlayerTelemetrySettings
import ru.arc.telemetry.PlayerTelemetryStore
import ru.arc.velocity.Velocity
import ru.ruscrafting.votes.config.SecretResolver
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/** The proxy writes the same SQL event journal using its own asynchronous durable outbox. */
object ProxyPlayerTelemetryModule : PluginModule {
    override val name = "PlayerTelemetry"
    override val priority = 28
    private val generation = AtomicLong()
    @Volatile private var closing: CompletableFuture<Unit>? = null
    @Volatile private var store: PlayerTelemetryStore? = null
    @Volatile private var listener: ProxyPlayerTelemetryListener? = null

    @Synchronized override fun init() {
        val epoch = generation.incrementAndGet()
        val config = ProxyConfigs.module("player-telemetry.yml")
        val sql = SqlModuleConfig(config)
        if (!config.bool("enabled", false) || !sql.enabled) return
        val passwordVariable = config.string("mysql.password-env", "").trim()
        val connection = sql.connection().let { base ->
            if (passwordVariable.isEmpty()) base else base.copy(password =
                SecretResolver(Velocity.requireDataFolder()).require(passwordVariable).revealForCryptography())
        }
        val instance = PlayerTelemetryStore(connection, Velocity.requireDataFolder(),
            Velocity.serverName, PlayerTelemetrySettings.from(config))
        store = instance
        instance.start().whenComplete { _, failure -> synchronized(this) {
            if (generation.get() != epoch || store !== instance) return@synchronized
            if (failure != null) {
                Velocity.logger?.warn("Player telemetry local recovery failed: {}", failure.javaClass.simpleName)
            } else if (generation.get() == epoch && store === instance) {
                val observer = ProxyPlayerTelemetryListener(instance,
                    ProxyProductConfig.from(ProxyConfigs.module("metrics.yml")).qaPlayerNames)
                listener = observer
                Velocity.requireProxyServer().eventManager.register(Velocity.requirePlugin(), observer)
                Velocity.requireProxyServer().allPlayers.forEach { observer.attach(it, true) }
            }
        } }
    }

    fun health(): Map<String, Any?> = mapOf("storage" to store?.health()?.asMap(), "sessionScope" to "proxy_connection")

    fun metricSnapshot(): List<ru.arc.metrics.core.MetricPoint> {
        val health = store?.health()
        fun point(suffix: String, help: String, value: Number) =
            ru.arc.metrics.core.MetricPoint("arc_player_telemetry_$suffix", help, value.toDouble())
        return listOf(
            point("accepting", "Player telemetry capture accepts events", if (health?.accepting == true) 1 else 0),
            point("sql_ready", "Player telemetry SQL sender is ready", if (health?.sqlReady == true) 1 else 0),
            point("queued_events", "Player telemetry events awaiting local journal commit", health?.queuedEvents ?: 0),
            point("durable_events", "Player telemetry journal events awaiting SQL acknowledgement", health?.durableEvents ?: 0),
            point("durable_bytes", "Player telemetry local journal bytes", health?.durableBytes ?: 0),
            point("delivered_events_since_start", "Player telemetry SQL acknowledgements since process start", health?.deliveredEventsSinceStart ?: 0),
            point("retries_since_start", "Player telemetry delivery retries since process start", health?.retriesSinceStart ?: 0),
            point("dropped_events_since_start", "Player telemetry capture losses since process start", health?.droppedEventsSinceStart ?: 0),
            point("corrupt_records", "Player telemetry quarantined journal records", health?.corruptRecords ?: 0),
            point("saturated", "Player telemetry queue or journal is saturated", if (health?.saturated == true) 1 else 0),
            point("coverage_gap", "Player telemetry has a persisted possible or known coverage gap", if (health?.coverageGapFrom != null) 1 else 0),
            point("oldest_outbox_age_seconds", "Age of oldest pending player telemetry event", health?.oldestOutboxAt?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0) / 1000.0) } ?: 0),
        )
    }

    @Synchronized override fun reload() {
        val previous = stopCapture("reload")
        val epoch = generation.get()
        val completion = previous?.closeAsync() ?: closing ?: CompletableFuture.completedFuture(Unit)
        closing = completion
        completion.whenComplete { _, failure -> synchronized(this) {
            if (generation.get() == epoch) {
                if (failure == null) init()
                else Velocity.logger?.warn("Player telemetry reload stopped because its previous journal did not close cleanly")
            }
        } }

    }

    override fun shutdown() {
        val completion = synchronized(this) {
            val previous = stopCapture("shutdown")
            closing = previous?.closeAsync() ?: closing
            closing
        }
        // Only shutdown waits; gameplay and reload never wait for disk or network.
        runCatching { completion?.get(10, TimeUnit.SECONDS) }.onFailure {
            Velocity.logger?.warn("Player telemetry shutdown did not finish within its bound: {}", it.javaClass.simpleName)
        }
    }

    @Synchronized private fun stopCapture(reason: String): PlayerTelemetryStore? {
        generation.incrementAndGet()
        listener?.let {
            Velocity.requireProxyServer().eventManager.unregisterListener(Velocity.requirePlugin(), it)
            it.close(reason)
        }
        listener = null
        return store.also { store = null }
    }
}
