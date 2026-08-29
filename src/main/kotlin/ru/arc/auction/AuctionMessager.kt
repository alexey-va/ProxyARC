package ru.arc.auction

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.slf4j.LoggerFactory
import ru.arc.discord.DiscordBot
import ru.arc.velocity.Velocity
import ru.arc.redis.ChannelListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AuctionMessager(
    @JvmField val channelPartial: String,
    @JvmField val channelAll: String,
    @JvmField val channelSales: String = "arc.auction_sale_events",
    private val discordBotProvider: () -> DiscordBot? = { Velocity.discordBot },
) : ChannelListener {

    private val log = LoggerFactory.getLogger(AuctionMessager::class.java)
    private val gson = Gson()

    @JvmField
    val map: MutableMap<UUID, AuctionItemDto> = ConcurrentHashMap()

    private val snapshotsByOrigin = mutableMapOf<String, MutableMap<UUID, AuctionItemDto>>()

    @Synchronized
    override fun consume(channel: String, message: String, originServer: String) {
        if (channel == channelSales) {
            consumeSale(message, originServer)
            return
        }

        val listType = object : TypeToken<List<AuctionItemDto>>() {}.type
        val auctionItemDtos =
            runCatching { gson.fromJson<List<AuctionItemDto>>(message, listType) }
                .getOrElse { error ->
                    log.warn("Ignoring malformed auction update from {}", originServer, error)
                    return
                } ?: emptyList()

        val origin = originServer.ifBlank { UNKNOWN_ORIGIN }
        if (channel == channelAll) {
            val replacement = mutableMapOf<UUID, AuctionItemDto>()
            applyUpdate(replacement, auctionItemDtos, origin)
            snapshotsByOrigin[origin] = replacement
        } else {
            val snapshot = snapshotsByOrigin.getOrPut(origin) { mutableMapOf() }
            applyUpdate(snapshot, auctionItemDtos, origin)
        }

        map.clear()
        snapshotsByOrigin.toSortedMap().values.forEach { snapshot ->
            snapshot.forEach { (id, item) -> map[id] = item }
        }

        val dtos = map.values.sortedBy { it.priority }
        discordBotProvider()?.updateAuctionItems(dtos)
            ?: log.debug("Skipping auction Discord update because the bot is not ready")
    }

    private fun applyUpdate(
        snapshot: MutableMap<UUID, AuctionItemDto>,
        updates: List<AuctionItemDto>,
        originServer: String,
    ) {
        for (item in updates) {
            try {
                val id = UUID.fromString(item.uuid)
                if (item.exist) {
                    snapshot[id] = item
                } else {
                    snapshot.remove(id)
                }
            } catch (e: Exception) {
                log.warn("Ignoring invalid auction item from {}: {}", originServer, item, e)
            }
        }
    }

    private fun consumeSale(
        message: String,
        originServer: String,
    ) {
        val event =
            runCatching { gson.fromJson(message, AuctionSaleEventDto::class.java) }
                .getOrElse { error ->
                    log.warn("Ignoring malformed auction sale event from {}", originServer, error)
                    return
                } ?: return
        if (!validSale(event)) {
            log.warn("Ignoring invalid auction sale event from {}", originServer)
            return
        }
        discordBotProvider()?.notifyAuctionSold(event)
    }

    companion object {
        internal fun validSale(event: AuctionSaleEventDto): Boolean =
            event.listingId.length in 1..64 &&
                PLAYER_NAME.matches(event.sellerName) &&
                PLAYER_NAME.matches(event.buyerName) &&
                event.itemDisplay.isNotBlank() && event.itemDisplay.length <= 200 &&
                event.amount in 1..1_000_000 && event.price.isNotBlank() &&
                event.price.length <= 100 && event.occurredAt >= 0

        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        private const val UNKNOWN_ORIGIN = "unknown"
    }
}
