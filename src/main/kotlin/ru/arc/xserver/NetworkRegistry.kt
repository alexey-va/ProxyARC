package ru.arc.xserver

import ru.arc.redis.RedisOperations
import ru.arc.ai.config.LlmModuleConfig
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.tools.PlayerServerResolver
import ru.arc.ai.tools.ToolRpcClient
import ru.arc.auction.AuctionMessager
import ru.arc.velocity.Velocity

class NetworkRegistry(
    private val redis: RedisOperations,
) : AutoCloseable {
    private var auctionMessager: AuctionMessager? = null
    private var toolRpcClient: ToolRpcClient? = null

    @Synchronized
    fun init() {
        close()
        try {
            val auction = AuctionMessager("arc.auction_items", "arc.auction_items_all")
            auctionMessager = auction
            redis.registerChannelUnique(auction.channelPartial, auction)
            redis.registerChannelUnique(auction.channelAll, auction)

            val dataPath = Velocity.dataFolder ?: return
            val llmConfig = LlmModuleConfig.load(dataPath)
            Velocity.llmClient = OpenRouterLlmClient.create(llmConfig)

            val resolver =
                PlayerServerResolver { playerName ->
                    Velocity.playerListAnnouncer?.serverForUsername(playerName)
                }
            val rpc = ToolRpcClient(redis, llmConfig, resolver, expectedResponses = 2)
            try {
                rpc.start()
            } catch (error: Exception) {
                rpc.close()
                throw error
            }
            toolRpcClient = rpc
            ToolRpcClient.instance = rpc
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        auctionMessager?.let { auction ->
            redis.unregisterChannel(auction.channelPartial, auction)
            redis.unregisterChannel(auction.channelAll, auction)
        }
        auctionMessager = null

        toolRpcClient?.let { rpc ->
            rpc.close()
            if (ToolRpcClient.instance === rpc) {
                ToolRpcClient.instance = null
            }
        }
        toolRpcClient = null
        Velocity.llmClient = null
    }
}
