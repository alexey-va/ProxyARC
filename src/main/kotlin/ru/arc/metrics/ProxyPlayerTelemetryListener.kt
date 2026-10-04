package ru.arc.metrics

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.command.CommandExecuteEvent
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.event.player.ServerResourcePackSendEvent
import com.velocitypowered.api.proxy.Player
import ru.arc.product.ProductCommandClassifier
import ru.arc.telemetry.PlayerTelemetryEvent
import ru.arc.telemetry.PlayerTelemetryStore
import ru.arc.velocity.Velocity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Velocity callbacks snapshot only identifiers and timings; the store owns all blocking work. */
internal class ProxyPlayerTelemetryListener(private val store: PlayerTelemetryStore, private val qaNames: Set<String>) {
    private data class Visit(val id: String, val name: String, val started: Long)
    private val visits = ConcurrentHashMap<UUID, Visit>()
    private val packs = ResourcePackJourneyClock()
    @Volatile private var closed = false

    @Synchronized fun attach(player: Player, resumed: Boolean) {
        if (closed) return
        val previous = visits.putIfAbsent(player.uniqueId, Visit(UUID.randomUUID().toString(), player.username, System.currentTimeMillis()))
        if (previous != null) return
        record(player, if (resumed) "connection.resume" else "connection.login", attributes = mapOf("censoredStart" to resumed.toString()))
    }

    @Subscribe(async = true)
    fun onLogin(event: PostLoginEvent) = attach(event.player, false)

    @Subscribe(async = true)
    fun onConnected(event: ServerConnectedEvent) {
        record(event.player, "server.connected", event.server.serverInfo.name,
            mapOf("fromServer" to event.previousServer.map { it.serverInfo.name }.orElse("none")))
    }

    @Subscribe(order = PostOrder.CUSTOM, priority = Short.MIN_VALUE, async = true)
    fun onPreConnect(event: ServerPreConnectEvent) {
        if (!event.result.isAllowed) record(event.player, "server.denied", event.originalServer.serverInfo.name)
    }

    @Subscribe(async = true)
    fun onKick(event: KickedFromServerEvent) = record(event.player, "server.kick", event.server.serverInfo.name,
        mapOf("duringConnect" to event.kickedDuringServerConnect().toString()))

    @Subscribe(order = PostOrder.CUSTOM, priority = Short.MIN_VALUE, async = true)
    fun onCommand(event: CommandExecuteEvent) {
        val player = event.commandSource as? Player ?: return
        val root = ProductCommandClassifier.root(event.command) ?: return
        if (Velocity.requireProxyServer().commandManager.hasCommand(root)) record(player, "command", root)
    }

    @Subscribe(order = PostOrder.CUSTOM, priority = Short.MIN_VALUE, async = true)
    fun onPackSent(event: ServerResourcePackSendEvent) {
        val player = event.serverConnection.player
        val pack = event.providedResourcePack
        if (event.result.isAllowed) packs.sent(player.uniqueId, pack.id, System.currentTimeMillis())
        record(player, "resource_pack.sent", pack.id.toString(), mapOf("allowed" to event.result.isAllowed.toString(),
            "origin" to "backend", "packHash" to pack.hash.hex()))
    }

    @Subscribe(async = true)
    fun onPackStatus(event: PlayerResourcePackStatusEvent) {
        val id = event.packId ?: event.packInfo?.id
        val detail = if (id != null) packs.status(event.player.uniqueId, id, event.status.name, System.currentTimeMillis()) else emptyMap()
        record(event.player, "resource_pack.status", id?.toString(), detail + mapOf("status" to event.status.name,
            "packHash" to event.packInfo?.hash.hex()))
    }

    @Subscribe(async = true)
    @Synchronized fun onDisconnect(event: DisconnectEvent) {
        if (closed) return
        record(event.player, "connection.end", attributes = buildMap {
            put("reason", "connection_closed")
            put("loginStatus", event.loginStatus.name)
            put("censored", "false")
            visits[event.player.uniqueId]?.let { put("durationMs", (System.currentTimeMillis() - it.started).coerceAtLeast(0).toString()) }
        })
        visits.remove(event.player.uniqueId)
        packs.remove(event.player.uniqueId)
    }

    @Synchronized fun close(reason: String) {
        if (closed) return
        closed = true
        visits.forEach { (id, visit) -> offer(id, visit, "connection.end", null,
            mapOf("reason" to reason, "censored" to "true", "durationMs" to (System.currentTimeMillis() - visit.started).coerceAtLeast(0).toString())) }
        visits.clear()
        packs.clear()
    }

    @Synchronized private fun record(player: Player, event: String, subject: String? = null, attributes: Map<String, String> = emptyMap()) {
        if (!closed) offer(player.uniqueId, visits[player.uniqueId], event, subject, attributes)
    }

    private fun offer(player: UUID, visit: Visit?, event: String, subject: String?, attributes: Map<String, String>) {
        runCatching { store.offer(PlayerTelemetryEvent(eventId = UUID.randomUUID().toString(), occurredAt = System.currentTimeMillis(),
            server = Velocity.serverName, playerId = player.toString(), playerName = visit?.name, sessionId = visit?.id,
            source = "proxy", event = event, subject = subject, qa = visit?.name?.lowercase() in qaNames, attributes = attributes)) }
            .onFailure { store.noteCaptureLoss() }
    }

    private fun ByteArray?.hex(): String = this?.let { java.util.HexFormat.of().formatHex(it) } ?: "unknown"
}

/** Pack timings distinguish backend send observations from proxy packs first observed at acceptance. */
internal class ResourcePackJourneyClock {
    private data class Pack(val sent: Long? = null, val accepted: Long? = null, val downloaded: Long? = null)
    private val pending = mutableMapOf<Pair<UUID, UUID>, Pack>()

    @Synchronized fun sent(player: UUID, pack: UUID, now: Long) {
        if (pending.size < 4_096 || player to pack in pending) pending[player to pack] = Pack(sent = now)
    }

    @Synchronized fun status(player: UUID, pack: UUID, status: String, now: Long): Map<String, String> {
        val key = player to pack
        val previous = pending[key] ?: Pack()
        val current = when (status) {
            "ACCEPTED" -> previous.copy(accepted = previous.accepted ?: now)
            "DOWNLOADED" -> previous.copy(downloaded = now)
            else -> previous
        }
        if (status in setOf("ACCEPTED", "DOWNLOADED")) {
            if (pending.size < 4_096 || key in pending) pending[key] = current
        } else pending.remove(key)
        return buildMap {
            val baseline = current.sent ?: current.accepted
            put("timingBasis", if (current.sent != null) "backend_send" else if (current.accepted != null) "accepted" else "unknown")
            baseline?.let { put("elapsedMs", (now - it).coerceAtLeast(0).toString()) }
            if (status == "DOWNLOADED" && current.accepted != null) put("downloadMs", (now - current.accepted).coerceAtLeast(0).toString())
            if (status == "SUCCESSFUL" || status == "SUCCESSFULLY_LOADED") current.downloaded?.let {
                put("applyMs", (now - it).coerceAtLeast(0).toString())
            }
        }
    }
    @Synchronized fun remove(player: UUID) { pending.keys.removeIf { it.first == player } }
    @Synchronized fun clear() = pending.clear()
}
