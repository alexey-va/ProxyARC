package ru.arc.ops

import java.util.concurrent.CompletableFuture

interface DiscordOpsGateway {
    fun isReady(): Boolean

    fun isChannelAllowed(
        channelId: String,
        allowedChannelIds: Set<String>,
    ): Boolean

    fun listChannels(allowedChannelIds: Set<String>): Map<String, Any?>

    fun readHistory(request: DiscordHistoryRequest): CompletableFuture<Map<String, Any?>>

    fun readMessage(request: DiscordMessageRequest): CompletableFuture<Map<String, Any?>>

    fun sendMessage(request: DiscordSendRequest): CompletableFuture<Map<String, Any?>>
}

data class DiscordHistoryRequest(
    val channelId: String,
    val limit: Int,
    val beforeMessageId: String? = null,
)

data class DiscordMessageRequest(
    val channelId: String,
    val messageId: String,
)

data class DiscordSendRequest(
    val channelId: String,
    val content: String,
    val replyToMessageId: String? = null,
)
