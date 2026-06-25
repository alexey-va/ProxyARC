package ru.arc.ai.tools

import ru.arc.ai.tools.ToolRpcClient.Companion.instance

interface RemoteToolSupport {
    fun executeRemote(): java.util.concurrent.CompletableFuture<Any>
}

fun formatToolResults(result: ToolInvokeResult): String =
    result.serverResults.entries.joinToString("\n") { (server, value) -> "[$server] $value" }
        .ifBlank { result.errors.entries.joinToString("\n") { (server, err) -> "[$server] ERROR: $err" } }

fun invokeRemoteTool(
    toolName: String,
    payload: com.google.gson.JsonElement,
    routing: ToolRouting = ToolRouting.Broadcast,
): java.util.concurrent.CompletableFuture<Any> {
    val client = instance ?: error("ToolRpcClient not initialized")
    return client.invoke(toolName, payload, routing, atLeastOneResponse = true)
        .thenApply { formatToolResults(it) as Any }
}
