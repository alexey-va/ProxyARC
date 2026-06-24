package ru.arc.auction

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ru.arc.discord.DiscordBot
import ru.arc.xserver.ChannelListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AuctionMessager(
    @JvmField val channelPartial: String,
    @JvmField val channelAll: String,
    @JvmField val discordBot: DiscordBot?,
) : ChannelListener {

    @JvmField
    val map: MutableMap<UUID, AuctionItemDto> = ConcurrentHashMap()

    override fun consume(channel: String, message: String, originServer: String) {
        if (channel == channelAll) {
            map.clear()
        }

        val gson = Gson()
        val listType = object : TypeToken<List<AuctionItemDto>>() {}.type
        val auctionItemDtos: List<AuctionItemDto> = gson.fromJson(message, listType)

        for (auctionItemDto in auctionItemDtos) {
            try {
                if (auctionItemDto.exist) {
                    map[UUID.fromString(auctionItemDto.uuid)] = auctionItemDto
                } else {
                    map.remove(UUID.fromString(auctionItemDto.uuid))
                }
            } catch (e: Exception) {
                println("Error: $auctionItemDto")
                e.printStackTrace()
            }
        }

        val dtos = map.values
            .sortedBy { it.priority }

        if (discordBot != null) {
            discordBot.updateAuctionItems(dtos)
        } else {
            println("Discord bot is null!")
        }
    }
}
