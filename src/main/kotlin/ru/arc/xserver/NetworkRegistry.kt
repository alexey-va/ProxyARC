package ru.arc.xserver

import org.slf4j.LoggerFactory
import ru.arc.ai.config.LlmModuleConfig
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.llm.SimpleChatService
import ru.arc.ai.npc.NpcChatRpcServer
import ru.arc.ai.npc.NpcDialogueConfig
import ru.arc.ai.npc.NpcDialogueService
import ru.arc.ai.tools.PlayerServerResolver
import ru.arc.ai.tools.ToolRpcClient
import ru.arc.auction.AuctionMessager
import ru.arc.redis.RedisOperations
import ru.arc.redis.resourcepack.ResourcePackPublication
import ru.arc.velocity.Velocity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class NetworkRegistry(
    private val redis: RedisOperations,
) : AutoCloseable {
    private var auctionMessager: AuctionMessager? = null
    private var toolRpcClient: ToolRpcClient? = null
    private var npcChatRpcServer: NpcChatRpcServer? = null
    private var npcChatExecutor: ExecutorService? = null
    private var resourcePackHashRefreshListener: ResourcePackHashRefreshListener? = null

    @Synchronized
    fun init() {
        close()
        try {
            val auction = AuctionMessager("arc.auction_items", "arc.auction_items_all")
            auctionMessager = auction
            redis.registerChannelUnique(auction.channelPartial, auction)
            redis.registerChannelUnique(auction.channelAll, auction)
            redis.registerChannelUnique(auction.channelSales, auction)

            val resourcePackListener =
                ResourcePackHashRefreshListener(
                    commandAvailable = {
                        Velocity.requireProxyServer().commandManager.hasCommand(RESOURCE_PACK_COMMAND)
                    },
                    executeGenerateHashes = {
                        Velocity.requireProxyServer().commandManager.executeAsync(
                            Velocity.requireProxyServer().consoleCommandSource,
                            "$RESOURCE_PACK_COMMAND generatehashes",
                        )
                    },
                    acknowledge = { originServer, request ->
                        redis.saveMapEntries(
                            ResourcePackPublication.ACK_KEY,
                            originServer,
                            ResourcePackPublication.encodeAcknowledgement(request),
                        )
                    },
                    info = { message -> log.info(message) },
                    warn = { message -> log.warn(message) },
                    error = { message, failure -> log.error(message, failure) },
                )
            resourcePackHashRefreshListener = resourcePackListener
            redis.registerChannelUnique(ResourcePackPublication.CHANNEL, resourcePackListener)

            val dataPath = Velocity.dataFolder ?: return
            // Keep the live-only API key in llm.yml while the tracked network route
            // is supplied independently. This prevents config deploys from replacing
            // the credential with the repository's deliberate `none` placeholder.
            val llmConfig = LlmModuleConfig.load(dataPath, LLM_NETWORK_RESOURCE)
            val llmClient = OpenRouterLlmClient.create(llmConfig)
            Velocity.llmClient = llmClient

            val dialogueConfig = NpcDialogueConfig.load(dataPath)
            val dialogueExecutor =
                Executors.newFixedThreadPool(NPC_CHAT_THREADS) { runnable ->
                    Thread(runnable, "proxyarc-npc-chat-${npcChatThreadNumber.incrementAndGet()}").apply {
                        isDaemon = true
                    }
                }
            val dialogueServer =
                NpcChatRpcServer(
                    redis = redis,
                    config = llmConfig,
                    handler = NpcDialogueService(dialogueConfig, SimpleChatService(llmClient, dialogueExecutor)),
                )
            try {
                dialogueServer.start()
            } catch (error: Exception) {
                dialogueExecutor.shutdownNow()
                throw error
            }
            npcChatExecutor = dialogueExecutor
            npcChatRpcServer = dialogueServer

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
            redis.unregisterChannel(auction.channelSales, auction)
        }
        auctionMessager = null

        resourcePackHashRefreshListener?.let { listener ->
            redis.unregisterChannel(ResourcePackPublication.CHANNEL, listener)
        }
        resourcePackHashRefreshListener = null

        toolRpcClient?.let { rpc ->
            rpc.close()
            if (ToolRpcClient.instance === rpc) {
                ToolRpcClient.instance = null
            }
        }
        toolRpcClient = null

        npcChatRpcServer?.close()
        npcChatRpcServer = null
        npcChatExecutor?.shutdownNow()
        npcChatExecutor = null
        Velocity.llmClient = null
    }

    companion object {
        private const val RESOURCE_PACK_COMMAND = "velocityresourcepacks"
        private const val LLM_NETWORK_RESOURCE = "llm-network.yml"
        private const val NPC_CHAT_THREADS = 4
        private val npcChatThreadNumber = AtomicInteger()
        private val log = LoggerFactory.getLogger(ResourcePackHashRefreshListener::class.java)
    }
}
