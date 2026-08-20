package ru.arc.ai.npc

import ru.arc.ai.llm.ChatTurn
import ru.arc.ai.llm.SimpleChatService
import java.util.concurrent.CompletableFuture

class NpcDialogueService(
    private val config: NpcDialogueConfig,
    private val chat: SimpleChatService,
) : NpcChatRequestHandler {

    override fun complete(request: NpcChatRequest): CompletableFuture<String?> {
        if (!config.enabled) {
            return CompletableFuture.failedFuture(IllegalStateException("NPC dialogue is disabled"))
        }
        val persona =
            config.personas[request.personaId]
                ?: return CompletableFuture.failedFuture(IllegalArgumentException("Unknown NPC persona"))
        val system = config.systemPrompt(persona, request.playerName)
        val turns =
            buildList {
                request.history.forEach { add(ChatTurn(it.role, it.content)) }
                add(ChatTurn("user", request.message))
            }
        return chat.complete(
            model = persona.model,
            systemPrompt = system,
            history = turns,
            maxTokens = persona.maxTokens,
            temperature = persona.temperature,
        )
    }
}
