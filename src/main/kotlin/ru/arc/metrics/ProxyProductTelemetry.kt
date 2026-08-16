package ru.arc.metrics

import com.google.gson.Gson
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import ru.arc.Common
import ru.arc.config.Config
import ru.arc.metrics.core.MetricPoint
import ru.arc.product.ProductBackend
import ru.arc.product.ProductCommandClassifier
import ru.arc.product.ProductConnection
import ru.arc.product.ProductDetail
import ru.arc.product.ProductDetailType
import ru.arc.product.ProductEventKind
import ru.arc.product.ProductFeature
import ru.arc.product.ProductPath
import ru.arc.product.ProductPseudonym
import ru.arc.product.ProductSignal
import ru.arc.product.ProductWireCodec
import ru.arc.redis.RedisOperations
import java.util.Locale
import java.util.UUID

data class ProxyProductConfig(
    val enabled: Boolean = true,
    val networkEnabled: Boolean = true,
    val qaPlayerNames: Set<String> = setOf("codexqa_728", "grocermc"),
) {
    companion object {
        fun from(config: Config): ProxyProductConfig =
            ProxyProductConfig(
                enabled = config.bool("product-interest.enabled", true),
                networkEnabled = config.bool("product-interest.network-enabled", true),
                qaPlayerNames =
                    config
                        .stringList("product-interest.qa-player-names", listOf("CodexQA_728", "GrocerMC"))
                        .map { it.trim().lowercase(Locale.ROOT) }
                        .filter { it.matches(Regex("[a-z0-9_]{3,16}")) }
                        .toSet(),
            )
    }
}

private enum class ProxyProductEvent(val label: String) {
    COMMAND("command"),
    SERVER_CONNECT("server_connect"),
    SERVER_SWITCH("server_switch"),
    CONNECT_DENIED("connect_denied"),
    BACKEND_KICK_CONNECT("backend_kick_connect"),
    BACKEND_KICK_PLAY("backend_kick_play"),
    DISCONNECT("disconnect"),
}

/**
 * Velocity-only ingress for commands and backend transitions that Paper cannot
 * observe. Dynamic roots and server names are sent only through the bounded,
 * pseudonymous journey contract; Prometheus labels remain fixed enums.
 */
class ProxyProductTelemetry(
    registry: MeterRegistry,
    private val config: ProxyProductConfig,
    private val redis: RedisOperations?,
    rawSource: String,
    private val gson: Gson = Common.gson,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val source = ProductWireCodec.normalizeServer(rawSource) ?: "proxy"
    private val organicEvents = counters(registry, "organic")
    private val qaEvents = counters(registry, "qa")
    private val transitions =
        ProductBackend.entries.flatMap { from ->
            ProductBackend.entries.map { to ->
                (from to to) to
                    Counter.builder("arc_product_proxy_server_transitions")
                        .description("Successful Velocity backend transitions by bounded backend type")
                        .tags("from", from.label, "to", to.label)
                        .register(registry)
            }
        }.toMap()
    private val featureCounters =
        ProductFeature.entries.associateWith { feature ->
            Counter.builder("arc_product_proxy_command_features")
                .description("Product features reached through Velocity-consumed commands")
                .tag("feature", feature.label)
                .register(registry)
        }
    private val transportCounters =
        listOf("publish_attempt", "publish_unavailable", "publish_failed").associateWith { result ->
            Counter.builder("arc_product_proxy_transport")
                .description("Velocity product telemetry publication attempts")
                .tag("result", result)
                .register(registry)
        }

    fun command(
        playerId: UUID,
        playerName: String,
        commandLine: String,
    ) {
        val root = ProductCommandClassifier.root(commandLine) ?: return
        if (isQa(playerName)) {
            qaEvents.getValue(ProxyProductEvent.COMMAND).increment()
            return
        }
        organicEvents.getValue(ProxyProductEvent.COMMAND).increment()
        val player = ProductPseudonym.of(playerId.toString())
        publish(detail(player, ProductDetailType.COMMAND, root))
        val interest = ProductCommandClassifier.classify(commandLine) ?: return
        featureCounters.getValue(interest.feature).increment()
        if (interest.feature.path != ProductPath.NONE) {
            publish(
                signal(
                    player = player,
                    kind = ProductEventKind.PATH_INTEREST,
                    path = interest.feature.path,
                    activity = interest.feature.activity,
                ),
            )
        }
        publish(
            signal(
                player = player,
                kind = ProductEventKind.FEATURE_INTEREST,
                path = interest.feature.path,
                feature = interest.feature,
                activity = interest.feature.activity,
            ),
        )
        if (interest.event != ProductEventKind.FEATURE_INTEREST) {
            publish(
                signal(
                    player = player,
                    kind = interest.event,
                    feature = interest.feature,
                    activity = interest.feature.activity,
                ),
            )
        }
    }

    fun serverConnected(
        playerId: UUID,
        playerName: String,
        previousServer: String?,
        server: String,
    ) {
        val event = if (previousServer == null) ProxyProductEvent.SERVER_CONNECT else ProxyProductEvent.SERVER_SWITCH
        val connection = if (previousServer == null) ProductConnection.SERVER_CONNECT else ProductConnection.SERVER_SWITCH
        if (isQa(playerName)) {
            qaEvents.getValue(event).increment()
            return
        }
        organicEvents.getValue(event).increment()
        transitions.getValue(ProductBackend.classify(previousServer) to ProductBackend.classify(server)).increment()
        val player = ProductPseudonym.of(playerId.toString())
        ProductWireCodec.normalizeServer(server)?.let { publish(detail(player, ProductDetailType.SERVER, it)) }
        publish(detail(player, ProductDetailType.CONNECTION, connection.label))
    }

    fun connectDenied(
        playerId: UUID,
        playerName: String,
        targetServer: String,
    ) = connectionEvent(
        playerId,
        playerName,
        ProxyProductEvent.CONNECT_DENIED,
        ProductConnection.CONNECT_DENIED,
        targetServer,
    )

    fun backendKick(
        playerId: UUID,
        playerName: String,
        server: String,
        duringConnect: Boolean,
    ) = connectionEvent(
        playerId,
        playerName,
        if (duringConnect) ProxyProductEvent.BACKEND_KICK_CONNECT else ProxyProductEvent.BACKEND_KICK_PLAY,
        if (duringConnect) ProductConnection.BACKEND_KICK_CONNECT else ProductConnection.BACKEND_KICK_PLAY,
        server.takeIf { duringConnect },
    )

    fun disconnect(
        playerId: UUID,
        playerName: String,
        connection: ProductConnection,
    ) = connectionEvent(
        playerId,
        playerName,
        ProxyProductEvent.DISCONNECT,
        connection,
        null,
    )

    fun snapshot(networkReady: Boolean): List<MetricPoint> =
        listOf(
            MetricPoint("arc_product_proxy_telemetry_enabled", "Velocity product telemetry enabled state", 1.0),
            MetricPoint(
                "arc_product_proxy_telemetry_network_ready",
                "Whether Velocity can publish product journey events through Redis",
                if (config.networkEnabled && networkReady) 1.0 else 0.0,
            ),
            MetricPoint(
                "arc_product_proxy_command_coverage",
                "Coverage of commands consumed by Velocity rather than forwarded to Paper",
                1.0,
            ),
        )

    private fun connectionEvent(
        playerId: UUID,
        playerName: String,
        event: ProxyProductEvent,
        connection: ProductConnection,
        targetServer: String?,
    ) {
        if (isQa(playerName)) {
            qaEvents.getValue(event).increment()
            return
        }
        organicEvents.getValue(event).increment()
        val player = ProductPseudonym.of(playerId.toString())
        targetServer
            ?.let(ProductWireCodec::normalizeServer)
            ?.let { publish(detail(player, ProductDetailType.SERVER_TARGET, it)) }
        publish(detail(player, ProductDetailType.CONNECTION, connection.label))
    }

    private fun detail(
        player: String,
        type: ProductDetailType,
        key: String,
    ): ProductSignal =
        signal(
            player = player,
            kind = ProductEventKind.DETAIL,
            detail = ProductDetail(type, key),
        )

    private fun signal(
        player: String,
        kind: ProductEventKind,
        path: ProductPath = ProductPath.NONE,
        feature: ProductFeature? = null,
        activity: ru.arc.product.ProductActivity? = null,
        detail: ProductDetail? = null,
    ): ProductSignal =
        ProductSignal(
            eventId = ProductPseudonym.eventId(),
            source = source,
            player = player,
            occurredAt = clockMillis(),
            kind = kind,
            path = path,
            feature = feature,
            activity = activity,
            detail = detail,
        )

    private fun publish(signal: ProductSignal) {
        val publisher = redis
        if (!config.networkEnabled || publisher == null) {
            transportCounters.getValue("publish_unavailable").increment()
            return
        }
        transportCounters.getValue("publish_attempt").increment()
        runCatching {
            publisher.publish(ProductWireCodec.CHANNEL, ProductWireCodec.encode(signal, gson))
        }.onFailure {
            // Product analytics must never break command or connection events.
            transportCounters.getValue("publish_failed").increment()
        }
    }

    private fun isQa(playerName: String): Boolean = playerName.lowercase(Locale.ROOT) in config.qaPlayerNames

    private fun counters(registry: MeterRegistry, cohort: String): Map<ProxyProductEvent, Counter> =
        ProxyProductEvent.entries.associateWith { event ->
            Counter.builder("arc_product_proxy_events")
                .description("Velocity-only product journey events")
                .tags("event", event.label, "cohort", cohort)
                .register(registry)
        }
}
