package ru.arc.ai.tools

import com.google.gson.Gson
import ru.arc.xserver.ChannelListener
import ru.arc.xserver.RedisManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class ToolsMessager(
    private val redisManager: RedisManager,
    private val maxResponses: Int,
) : ChannelListener {

    override fun consume(channel: String, message: String, originServer: String) {
        val toolResponse = gson.fromJson(message, ToolResponse::class.java)
        val toolMessage = pendingResponses[toolResponse.uuid]
        if (toolMessage != null) {
            toolMessage.toolResult.serverResults[originServer] = toolResponse.result ?: ""
            if (toolMessage.atLeastOneResponse) {
                toolMessage.responseFuture.complete(toolMessage.toolResult)
            } else if (toolMessage.toolResult.serverResults.size >= maxResponses) {
                toolMessage.responseFuture.complete(toolMessage.toolResult)
            }
        }
        trimTimedOutResponses()
    }

    fun sendToolMessage(toolDto: Tool, atLeastOneResponse: Boolean): CompletableFuture<ToolResult> {
        trimTimedOutResponses()

        val uuid = UUID.randomUUID()
        val future = CompletableFuture<ToolResult>()
        val toolMessage = ToolMessage(
            uuid = uuid,
            toolDto = toolDto,
            toolName = toolDto.javaClass.simpleName,
            timestamp = System.currentTimeMillis(),
            atLeastOneResponse = atLeastOneResponse,
            responseFuture = future,
        )
        CompletableFuture.runAsync {
            val json = gson.toJson(toolMessage)
            redisManager.publish(CHANNEL_REQUEST_TOOLS, json)
        }
        pendingResponses[uuid] = toolMessage

        return future
    }

    private fun trimTimedOutResponses() {
        val now = System.currentTimeMillis()
        pendingResponses.entries.removeIf { (_, value) ->
            now - value.timestamp > RESPONSE_TIMEOUT_MS
        }
        pendingResponses.entries.removeIf { (_, value) ->
            value.responseFuture.isDone
        }
    }

    companion object {
        @JvmField
        var instance: ToolsMessager? = null

        const val CHANNEL_REQUEST_TOOLS: String = "arc.ai_tools_req"
        const val CHANNEL_RESPONSE_TOOLS: String = "arc.ai_tools_res"

        private const val RESPONSE_TIMEOUT_MS: Long = 30000
        private val pendingResponses: MutableMap<UUID, ToolMessage> = ConcurrentHashMap()
        private val gson = Gson()
    }
}
