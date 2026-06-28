package ru.arc.ai.routing.pipeline

import ru.arc.ai.routing.observe.BotReplyTracker
import ru.arc.ai.routing.observe.ChatLineFormatter
import ru.arc.ai.routing.observe.ChatLog
import ru.arc.config.Config
import ru.arc.velocity.Velocity

class ObserveStage(
    private val chatLog: ChatLog,
    private val formatter: ChatLineFormatter,
    private val botReplyTracker: BotReplyTracker,
    private val assistantConfig: Config,
) {
    fun process(context: PipelineContext): PipelineContext {
        val line = formatter.format(context.message, context.meta, botReplyTracker)
        chatLog.append(line)
        if (assistantConfig.bool("chat.observe-all-chat", true)) {
            Velocity.chatAssistant?.observeChat(line)
        }
        return context
    }
}
