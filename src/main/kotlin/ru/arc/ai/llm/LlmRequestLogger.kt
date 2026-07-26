package ru.arc.ai.llm

import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.completions.CompletionUsage
import org.slf4j.Logger

/** Structured LLM request/response logging for latency and context tuning. */
object LlmRequestLogger {
    fun logAssistantRequestStart(
        log: Logger,
        requestId: String,
        agent: String,
        mode: String,
        source: String,
        depth: Int,
        player: String?,
        triggerMessage: String?,
        queueWaitMs: Long,
        contextLayers: Int,
        historyMessages: Int,
        toolSchemas: Int,
        model: String,
    ) {
        log.info(
            "LLM → requestId={} agent={} mode={} source={} depth={} player={} queueWaitMs={} trigger=\"{}\" ctxLayers={} history={} toolSchemas={} model={}",
            requestId,
            agent,
            mode,
            source,
            depth,
            player ?: "?",
            queueWaitMs,
            LogPreview.of(triggerMessage, 80),
            contextLayers,
            historyMessages,
            toolSchemas,
            model,
        )
    }

    fun logAssistantRequestComplete(
        log: Logger,
        requestId: String,
        agent: String,
        mode: String,
        source: String,
        depth: Int,
        player: String?,
        model: String,
        latencyMs: Long,
        response: ChatCompletion,
        toolNames: List<String>,
    ) {
        val choice = response.choices().firstOrNull()
        val finish = choice?.finishReason()?.toString() ?: "null"
        val contentPreview =
            choice
                ?.message()
                ?.content()
                ?.orElse("")
                ?.trim()
                ?.replace('\n', ' ')
                ?.take(60)
                .orEmpty()
        val usageLine = formatUsage(response.usage().orElse(null))
        log.info(
            "LLM ← requestId={} agent={} mode={} source={} depth={} player={} model={} latencyMs={} outcome=success {} tools=[{}] finish={} content=\"{}\"",
            requestId,
            agent,
            mode,
            source,
            depth,
            player ?: "?",
            model,
            latencyMs,
            usageLine,
            toolNames.joinToString(",").ifEmpty { "-" },
            finish,
            LogPreview.of(contentPreview, 60),
        )
    }

    fun logRouterRequestStart(
        log: Logger,
        requestId: String,
        model: String,
        player: String?,
        message: String?,
        userChars: Int,
    ) {
        log.info(
            "LLM → requestId={} agent=router mode=route source=unknown depth=0 model={} player={} msg=\"{}\" userChars={}",
            requestId,
            model,
            player ?: "?",
            LogPreview.of(message, 80),
            userChars,
        )
    }

    fun logRouterRequestComplete(
        log: Logger,
        requestId: String,
        model: String,
        player: String?,
        latencyMs: Long,
        response: ChatCompletion,
    ) {
        val choice = response.choices().firstOrNull()
        val finish = choice?.finishReason()?.toString() ?: "null"
        val contentPreview =
            choice
                ?.message()
                ?.content()
                ?.orElse("")
                ?.trim()
                ?.replace('\n', ' ')
                ?.take(80)
                .orEmpty()
        val usageLine = formatUsage(response.usage().orElse(null))
        log.info(
            "LLM ← requestId={} agent=router mode=route source=unknown depth=0 model={} player={} latencyMs={} outcome=success {} finish={} content=\"{}\"",
            requestId,
            model,
            player ?: "?",
            latencyMs,
            usageLine,
            finish,
            LogPreview.of(contentPreview, 80),
        )
    }

    internal fun formatUsage(usage: CompletionUsage?): String {
        if (usage == null) return "tokens=?"
        val prompt = usage.promptTokens()
        val completion = usage.completionTokens()
        val cached =
            usage.promptTokensDetails().flatMap { it.cachedTokens() }.orElse(0L)
        return if (cached > 0L) {
            val pct = if (prompt > 0L) cached * 100 / prompt else 0L
            "prompt=$prompt cached=$cached($pct%) completion=$completion"
        } else {
            "prompt=$prompt completion=$completion"
        }
    }
}
