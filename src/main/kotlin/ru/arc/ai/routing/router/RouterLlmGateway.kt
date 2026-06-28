package ru.arc.ai.routing.router

import java.util.concurrent.CompletableFuture

fun interface RouterLlmGateway {
    fun complete(
        systemPrompt: String,
        userContent: String,
        model: String,
    ): CompletableFuture<String>
}
