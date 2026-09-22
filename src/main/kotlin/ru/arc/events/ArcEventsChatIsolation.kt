package ru.arc.events

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.player.ServerPreConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived proxy-side audience lease for ArcEvents participants on an
 * allowlisted backend.
 *
 * The backend is the only authority that can renew the lease. The carrier
 * connection and the target player must be the same UUID, and the target must
 * still be connected to the exact source connection on an allowed backend.
 */
internal class ArcEventsChatIsolation(
    private val nowNanos: () -> Long = System::nanoTime,
    private val settings: () -> ArcEventsChatSettings = { ArcEventsChatSettings() },
) {
    private val expiryByPlayer = ConcurrentHashMap<UUID, Lease>()
    @Volatile
    private var currentSettings: ArcEventsChatSettings = settings()

    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != CHANNEL) return
        // Consume this channel even when the payload or source is invalid.
        event.result = PluginMessageEvent.ForwardResult.handled()

        val source = event.source as? ServerConnection ?: return
        val target = event.target as? Player ?: return
        val carrier = source.player
        if (carrier.uniqueId != target.uniqueId) return
        val sourceServer = source.serverInfo.name
        if (!currentSettings.allows(sourceServer)) return
        val currentConnection = target.currentServer.orElse(null)
        if (currentConnection !== source) {
            return
        }
        val currentServer = currentConnection.serverInfo.name
        if (!currentSettings.allows(currentServer)) return

        val payload = event.data
        if (payload.size != PAYLOAD_SIZE || payload[0] != PROTOCOL_VERSION) return
        when (payload[1]) {
            ACTIVE -> expiryByPlayer[target.uniqueId] = Lease(source, sourceServer, deadline(nowNanos()))
            INACTIVE -> expiryByPlayer.remove(target.uniqueId)
            else -> Unit
        }
    }

    /** Clear before a successful backend transition so the old lease cannot cross servers. */
    @Subscribe(order = PostOrder.LAST, async = true)
    fun onServerPreConnect(event: ServerPreConnectEvent) {
        if (event.result.isAllowed) clear(event.player.uniqueId)
    }

    /** Covers connections completed without a pre-connect transition. */
    @Subscribe(async = true)
    fun onServerConnected(event: ServerConnectedEvent) {
        clear(event.player.uniqueId)
    }

    @Subscribe(async = true)
    fun onDisconnect(event: DisconnectEvent) {
        clear(event.player.uniqueId)
    }

    fun isIsolated(player: Player): Boolean {
        val lease = expiryByPlayer[player.uniqueId] ?: return false
        val currentConnection = player.currentServer.orElse(null)
        val current = currentSettings
        if (currentConnection == null ||
            currentConnection !== lease.connection ||
            !current.allows(lease.server) ||
            !currentConnection.serverInfo.name.equals(lease.server, ignoreCase = true)
        ) {
            expiryByPlayer.remove(player.uniqueId, lease)
            return false
        }
        val now = nowNanos()
        if (lease.expiresAtNanos - now > 0L) return true
        expiryByPlayer.remove(player.uniqueId, lease)
        return false
    }

    fun recipients(players: Iterable<Player>): List<Player> = players.filterNot(::isIsolated)

    /** Re-read hot-reloaded settings and revoke leases from removed backends. */
    fun reload() {
        val current = settings()
        currentSettings = current
        expiryByPlayer.forEach { (playerId, lease) ->
            if (!current.allows(lease.server)) expiryByPlayer.remove(playerId, lease)
        }
    }

    fun clear(playerId: UUID) {
        expiryByPlayer.remove(playerId)
    }

    fun clear() {
        expiryByPlayer.clear()
    }

    private fun deadline(now: Long): Long = now + LEASE_NANOS

    private data class Lease(
        val connection: ServerConnection,
        val server: String,
        val expiresAtNanos: Long,
    )

    companion object {
        val CHANNEL: MinecraftChannelIdentifier = MinecraftChannelIdentifier.from("arc:events_chat")
        const val PROTOCOL_VERSION: Byte = 1
        const val PAYLOAD_SIZE = 2
        const val ACTIVE: Byte = 1
        const val INACTIVE: Byte = 0

        private const val LEASE_NANOS = 15_000_000_000L
    }
}
