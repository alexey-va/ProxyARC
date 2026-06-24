package ru.arc.ai.tools

import java.util.UUID
import java.util.concurrent.CompletableFuture

data class ToolMessage(
    @JvmField val uuid: UUID,
    @JvmField val toolName: String,
    @JvmField val toolDto: Tool,
    @JvmField val timestamp: Long,
    @JvmField @Transient var responseFuture: CompletableFuture<ToolResult>,
    @JvmField @Transient var toolResult: ToolResult = ToolResult(),
    @JvmField @Transient var atLeastOneResponse: Boolean = false,
)
