package ru.arc.dungeonparty

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.ConnectionRequestBuilder
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import org.slf4j.LoggerFactory
import ru.arc.Utils
import ru.arc.core.Tasks
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class DungeonPartyGatherManager(
    private val server: ProxyServer,
    private val targetServer: String = TARGET_SERVER,
    private val scheduleLater: (Long, Runnable) -> Unit = { ticks, task -> Tasks.scheduler.runLater(ticks, task) },
) {
    private val pending = ConcurrentHashMap<UUID, DungeonPartyGatherRequest>()

    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != REQUEST_CHANNEL) return
        event.result = PluginMessageEvent.ForwardResult.handled()

        val source = event.source as? com.velocitypowered.api.proxy.ServerConnection ?: return
        val carrier = source.player
        val target = event.target as? Player ?: return
        if (target.uniqueId != carrier.uniqueId) return

        val request = runCatching { DungeonPartyGatherRequest.decode(event.data) }
            .getOrElse { failure ->
                log.warn("Rejected malformed dungeon party request from {}: {}", carrier.username, failure.message)
                return
            }
        if (request.leaderId != carrier.uniqueId) {
            log.warn("Rejected dungeon party request {} because carrier {} is not its leader", request.operationId, carrier.username)
            return
        }
        if (pending.putIfAbsent(carrier.uniqueId, request) != null) return
        gather(carrier, request)
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        pending.remove(event.player.uniqueId)
    }

    fun shutdown() = pending.clear()

    private fun gather(leader: Player, request: DungeonPartyGatherRequest) {
        val target = server.getServer(targetServer).orElse(null)
        if (target == null) {
            fail(leader, request, "сервер экспедиций недоступен")
            return
        }
        val roster = selectOnlineRoster(request) { server.getPlayer(it).isPresent }
            .mapNotNull { server.getPlayer(it).orElse(null) }
        val transfers = roster.map { player ->
            if (isOnTarget(player)) CompletableFuture.completedFuture(true)
            else player.createConnectionRequest(target).connect().handle { result, failure ->
                failure == null && result != null &&
                    (result.isSuccessful || result.status == ConnectionRequestBuilder.Status.ALREADY_CONNECTED)
            }
        }
        CompletableFuture.allOf(*transfers.toTypedArray()).whenComplete { _, _ ->
            scheduleLater(DELIVERY_DELAY_TICKS, Runnable { deliver(request, roster.map(Player::getUniqueId)) })
        }
    }

    private fun deliver(request: DungeonPartyGatherRequest, selectedIds: List<UUID>, attempt: Int = 0) {
        if (pending[request.leaderId] != request) return
        val leader = server.getPlayer(request.leaderId).orElse(null)
        if (leader == null || !isOnTarget(leader)) {
            if (attempt < MAX_DELIVERY_ATTEMPTS) {
                scheduleLater(RETRY_DELAY_TICKS, Runnable { deliver(request, selectedIds, attempt + 1) })
            } else if (leader != null) {
                fail(leader, request, "не удалось перейти на сервер экспедиций")
            } else {
                pending.remove(request.leaderId, request)
            }
            return
        }

        val arrived = selectedIds.filter { memberId ->
            server.getPlayer(memberId).map(::isOnTarget).orElse(false)
        }
        val roster = listOf(request.leaderId) + arrived.filterNot(request.leaderId::equals)
        val connection = leader.currentServer.orElse(null) ?: return
        val sent = connection.sendPluginMessage(
            ARRIVAL_CHANNEL,
            DungeonPartyGatherArrival(request.operationId, request.leaderId, roster).encode(),
        )
        if (sent) {
            pending.remove(request.leaderId, request)
            log.info("Gathered dungeon party {} with {} player(s) on {}", request.operationId, roster.size, targetServer)
        } else if (attempt < MAX_DELIVERY_ATTEMPTS) {
            scheduleLater(RETRY_DELAY_TICKS, Runnable { deliver(request, selectedIds, attempt + 1) })
        } else {
            fail(leader, request, "сервер экспедиций не принял группу")
        }
    }

    private fun fail(leader: Player, request: DungeonPartyGatherRequest, reason: String) {
        pending.remove(request.leaderId, request)
        leader.sendMessage(Utils.mm("<red>Не удалось собрать группу: <white>$reason"))
        log.warn("Dungeon party request {} failed for {}: {}", request.operationId, leader.username, reason)
    }

    private fun isOnTarget(player: Player): Boolean = player.currentServer
        .map { it.serverInfo.name.equals(targetServer, ignoreCase = true) }
        .orElse(false)

    internal fun selectOnlineRoster(
        request: DungeonPartyGatherRequest,
        isOnline: (UUID) -> Boolean,
    ): List<UUID> {
        if (!isOnline(request.leaderId)) return emptyList()
        val others = request.memberIds.asSequence()
            .filterNot(request.leaderId::equals)
            .filter(isOnline)
            .take(DungeonPartyGatherArrival.MAX_PARTY_MEMBERS - 1)
            .toList()
        return listOf(request.leaderId) + others
    }

    companion object {
        val REQUEST_CHANNEL: MinecraftChannelIdentifier =
            MinecraftChannelIdentifier.from(DungeonPartyGatherRequest.CHANNEL)
        val ARRIVAL_CHANNEL: MinecraftChannelIdentifier =
            MinecraftChannelIdentifier.from(DungeonPartyGatherArrival.CHANNEL)
        private const val TARGET_SERVER = "classic"
        private const val DELIVERY_DELAY_TICKS = 10L
        private const val RETRY_DELAY_TICKS = 10L
        private const val MAX_DELIVERY_ATTEMPTS = 3
        private val log = LoggerFactory.getLogger(DungeonPartyGatherManager::class.java)
    }
}
