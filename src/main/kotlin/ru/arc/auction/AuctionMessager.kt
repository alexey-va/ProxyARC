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
    private val discordBotProvider: () -> DiscordBot? = { Velocity.discordBot },
) : ChannelListener {

    private val log = LoggerFactory.getLogger(AuctionMessager::class.java)
    private val gson = Gson()

    @JvmField
    val map: MutableMap<UUID, AuctionItemDto> = ConcurrentHashMap()

    override fun consume(channel: String, message: String, originServer: String) {
        if (channel == channelAll) {
            map.clear()
        }

        val listType = object : TypeToken<List<AuctionItemDto>>() {}.type
        val auctionItemDtos =
            runCatching { gson.fromJson<List<AuctionItemDto>>(message, listType) }
                .getOrElse { error ->
                    log.warn("Ignoring malformed auction update from {}", originServer, error)
                    return
                } ?: emptyList()

        for (auctionItemDto in auctionItemDtos) {
            try {
                if (auctionItemDto.exist) {
                    map[UUID.fromString(auctionItemDto.uuid)] = auctionItemDto
                } else {
                    map.remove(UUID.fromString(auctionItemDto.uuid))
                }
            } catch (e: Exception) {
                log.warn("Ignoring invalid auction item from {}: {}", originServer, auctionItemDto, e)
            }
        }

        val dtos = map.values
            .sortedBy { it.priority }

        val discordBot = discordBotProvider()
        if (discordBot != null) {
            discordBot.updateAuctionItems(dtos)
        } else {
            log.debug("Skipping auction Discord update because the bot is not ready")
        }
    }
}
