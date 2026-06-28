package ru.arc.ai.routing.dispatch.assistant

import com.velocitypowered.api.proxy.ProxyServer
import ru.arc.ai.Assistant
import ru.arc.ai.AssistantChatBridge
import ru.arc.ai.AssistantRunMode
import ru.arc.ai.routing.pipeline.PipelineContext
import ru.arc.velocity.Velocity

/** Runs the shared Skorin LLM agent for a routed scenario. */
class AssistantAgentDispatch(
    private val proxyServer: ProxyServer,
) {
    fun enqueue(
        context: PipelineContext,
        mode: AssistantRunMode,
        extraHistoryLines: List<Pair<String, String>> = emptyList(),
        deliverPublicReply: Boolean = false,
    ) {
        val assistant = assistant() ?: return
        val player = context.message.player
        val message = context.message.displayText

        // Trigger line is already in observeChat() from ObserveStage — do not duplicate in history.
        for ((content, name) in extraHistoryLines) {
            assistant.addChatMessage(content, name)
        }

        assistant.tryEnqueue(
            triggerPlayer = player,
            triggerMessage = message,
            mode = mode,
            triggerServer = context.message.server,
        ).thenAccept { result ->
            if (deliverPublicReply && result.hasReply) {
                AssistantChatBridge.deliverReply(
                    proxyServer = proxyServer,
                    rawReply = result.reply!!,
                    triggerPlayer = player,
                    triggerMessage = message,
                    rawModelContent = result.rawModelContent,
                )
            }
        }
    }

    fun assistant(): Assistant? = Velocity.chatAssistant
}
