package ru.arc.rtp

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.ConnectionRequestBuilder
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import org.slf4j.LoggerFactory
import ru.arc.Utils
import ru.arc.core.Tasks
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RtpRequestManager(
    private val server: ProxyServer,
    private val config: ProxyRtpConfig,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val requestIds: () -> UUID = UUID::randomUUID,
    private val scheduleLater: (Long, Runnable) -> Unit = { ticks, task ->
        Tasks.scheduler.runLater(ticks, task)
    },
) {
    private val pending = ConcurrentHashMap<UUID, PendingRtp>()

    fun request(
        player: Player,
        rawWorld: String?,
    ) {
        if (!config.enabled) {
            player.sendMessage(Utils.mm("<red>RTP временно отключён."))
            return
        }
        val explicitWorld = rawWorld?.let(::normalize)
        if (explicitWorld != null && explicitWorld !in config.allowedWorlds) {
            player.sendMessage(
                Utils.mm(
                    "<red>Неизвестный мир. Доступно: <white>${config.allowedWorlds.joinToString(", ")}",
                ),
            )
            return
        }
        val target =
            server.getServer(config.targetServer).orElse(null)
                ?: run {
                    player.sendMessage(Utils.mm("<red>Сервер RTP временно недоступен."))
                    log.error("RTP target server '{}' is not registered in Velocity", config.targetServer)
                    return
                }

        val now = clockMillis()
        pending[player.uniqueId]?.takeIf { it.expiresAt > now }?.let {
            player.sendMessage(Utils.mm("<yellow>Предыдущий запрос RTP ещё выполняется."))
            return
        }
        pending.remove(player.uniqueId)

        val alreadyOnTarget = isOnTarget(player, config.targetServer)
        val world =
            explicitWorld
                ?: if (alreadyOnTarget) {
                    NetworkRtpRequest.CURRENT_WORLD
                } else {
                    config.defaultWorld
                }
        val request =
            NetworkRtpRequest(
                requestId = requestIds(),
                playerId = player.uniqueId,
                worldName = world,
                targetServer = config.targetServer,
                mode = if (alreadyOnTarget) NetworkRtpMode.REGULAR else NetworkRtpMode.FIRST_ENTRY,
            )
        val item = PendingRtp(request, now + config.requestTimeoutMillis)
        pending[player.uniqueId] = item

        if (alreadyOnTarget) {
            deliver(player)
            return
        }

        config.transferMessage
            .takeIf(String::isNotBlank)
            ?.let { player.sendMessage(Utils.mm(it)) }
        player
            .createConnectionRequest(target)
            .connect()
            .whenComplete { result, failure ->
                if (failure != null) {
                    fail(player, item, "не удалось подключиться к survival", failure)
                    return@whenComplete
                }
                if (
                    result == null ||
                    (
                        !result.isSuccessful &&
                            result.status != ConnectionRequestBuilder.Status.ALREADY_CONNECTED
                    )
                ) {
                    fail(
                        player,
                        item,
                        "переход на survival отклонён (${result?.status ?: "unknown"})",
                    )
                    return@whenComplete
                }
                if (isOnTarget(player, request.targetServer)) {
                    scheduleAfterTransfer(player)
                }
            }
    }

    @Subscribe
    fun onServerPostConnect(event: ServerPostConnectEvent) {
        val item = pending[event.player.uniqueId] ?: return
        if (isOnTarget(event.player, item.request.targetServer)) {
            scheduleAfterTransfer(event.player)
        }
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        pending.remove(event.player.uniqueId)
    }

    /**
     * Consume this channel at the proxy boundary. Client-originated messages
     * must never be forwarded to ARC as trusted proxy requests.
     */
    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != CHANNEL) return
        event.result = PluginMessageEvent.ForwardResult.handled()
    }

    fun shutdown() {
        pending.clear()
    }

    internal fun pendingCount(): Int = pending.size

    internal fun pendingRequest(playerId: UUID): NetworkRtpRequest? = pending[playerId]?.request

    /**
     * Velocity can report a successful backend connection before Bukkit has
     * completed the player join. A plugin message sent in that window returns
     * true but is silently lost by the backend, so cross-server requests wait
     * one second before their first delivery. Regular requests for a player
     * already on survival remain immediate.
     */
    private fun scheduleAfterTransfer(player: Player) {
        val item = pending[player.uniqueId] ?: return
        if (!item.deliveryScheduled.compareAndSet(false, true)) return
        scheduleLater(
            POST_CONNECT_DELIVERY_DELAY_TICKS,
            Runnable {
                item.deliveryScheduled.set(false)
                deliver(player)
            },
        )
    }

    private fun deliver(player: Player) {
        val item = pending[player.uniqueId] ?: return
        if (clockMillis() > item.expiresAt) {
            fail(player, item, "запрос RTP устарел")
            return
        }
        if (!isOnTarget(player, item.request.targetServer)) return
        if (!item.delivering.compareAndSet(false, true)) return

        val sent =
            player.currentServer
                .map { it.sendPluginMessage(CHANNEL, item.request.encode()) }
                .orElse(false)
        if (sent) {
            pending.remove(player.uniqueId, item)
            log.info(
                "Delivered network RTP request {} for {} to {} world {}",
                item.request.requestId,
                player.username,
                item.request.targetServer,
                item.request.worldName,
            )
            return
        }

        item.delivering.set(false)
        if (item.attempts.incrementAndGet() < MAX_DELIVERY_ATTEMPTS) {
            scheduleLater(RETRY_DELAY_TICKS, Runnable { deliver(player) })
        } else {
            fail(player, item, "ARC не принял сетевой запрос RTP")
        }
    }

    private fun fail(
        player: Player,
        item: PendingRtp,
        reason: String,
        failure: Throwable? = null,
    ) {
        if (!pending.remove(player.uniqueId, item)) return
        player.sendMessage(Utils.mm("<red>Не удалось запустить RTP: <white>$reason"))
        if (failure == null) {
            log.warn("Network RTP request {} failed for {}: {}", item.request.requestId, player.username, reason)
        } else {
            log.warn("Network RTP request {} failed for {}: {}", item.request.requestId, player.username, reason, failure)
        }
    }

    private fun isOnTarget(
        player: Player,
        targetServer: String,
    ): Boolean =
        player.currentServer
            .map { it.serverInfo.name.equals(targetServer, ignoreCase = true) }
            .orElse(false)

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)

    private data class PendingRtp(
        val request: NetworkRtpRequest,
        val expiresAt: Long,
        val attempts: AtomicInteger = AtomicInteger(),
        val delivering: AtomicBoolean = AtomicBoolean(),
        val deliveryScheduled: AtomicBoolean = AtomicBoolean(),
    )

    companion object {
        val CHANNEL: MinecraftChannelIdentifier = MinecraftChannelIdentifier.from(NetworkRtpRequest.CHANNEL)
        private const val MAX_DELIVERY_ATTEMPTS = 3
        private const val RETRY_DELAY_TICKS = 10L
        private const val POST_CONNECT_DELIVERY_DELAY_TICKS = 20L
        private val log = LoggerFactory.getLogger(RtpRequestManager::class.java)
    }
}
