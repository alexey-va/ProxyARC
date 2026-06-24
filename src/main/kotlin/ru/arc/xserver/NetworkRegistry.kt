package ru.arc.xserver

import ru.arc.ai.tools.ToolsMessager
import ru.arc.auction.AuctionMessager
import ru.arc.discord.DiscordBot

class NetworkRegistry(
    private val redisManager: RedisManager,
) {
    fun init() {
        val auctionMessager = AuctionMessager("arc.auction_items", "arc.auction_items_all", DiscordBot.instance)
        redisManager.registerChannelUnique(auctionMessager.channelPartial, auctionMessager)
        redisManager.registerChannelUnique(auctionMessager.channelAll, auctionMessager)

        val toolsMessager = ToolsMessager(redisManager, 2)
        redisManager.registerChannelUnique(ToolsMessager.CHANNEL_RESPONSE_TOOLS, toolsMessager)
        ToolsMessager.instance = toolsMessager

        redisManager.init()
    }
}
