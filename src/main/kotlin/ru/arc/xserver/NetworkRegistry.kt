package ru.arc.xserver

import ru.arc.ai.config.LlmModuleConfig
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.tools.PlayerServerResolver
import ru.arc.ai.tools.ToolRpcClient
import ru.arc.auction.AuctionMessager
import ru.arc.discord.DiscordBot
import ru.arc.velocity.Velocity

class NetworkRegistry(
    private val redisManager: RedisManager,
) {
    fun init() {
        val auctionMessager = AuctionMessager("arc.auction_items", "arc.auction_items_all", DiscordBot.instance)
        redisManager.registerChannelUnique(auctionMessager.channelPartial, auctionMessager)
        redisManager.registerChannelUnique(auctionMessager.channelAll, auctionMessager)

        val dataPath = Velocity.dataFolder ?: return
        val llmConfig = LlmModuleConfig.load(dataPath)
        Velocity.llmClient = OpenRouterLlmClient.create(llmConfig)

        val resolver =
            PlayerServerResolver { playerName ->
                Velocity.playerListAnnouncer?.serverForUsername(playerName)
            }
        val toolRpcClient = ToolRpcClient(redisManager, llmConfig, resolver, expectedResponses = 2)
        toolRpcClient.start()
        ToolRpcClient.instance = toolRpcClient

        redisManager.init()
    }
}
