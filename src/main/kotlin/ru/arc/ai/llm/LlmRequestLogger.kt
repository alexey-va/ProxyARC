package ru.arc.ai.llm

import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.completions.CompletionUsage
import org.slf4j.Logger

/** Structured LLM request/response logging for latency and context tuning. */
object LlmRequestLogger {
    fun logAssistantRequestStart(
        log: Logger,
        agent: String,
        mode: String,
        depth: Int,
        player: String?,
        triggerMessage: String?,
        contextLayers: Int,
        historyMessages: Int,
        toolSchemas: Int,
        model: String,
    ) {
        log.info(
            "LLM → agent={} mode={} depth={} player={} trigger=\"{}\" ctxLayers={} history={} toolSchemas={} model={}",
            agent,
            mode,
            depth,
            player ?: "?",
            triggerMessage?.take(80) ?: "",
            contextLayers,
            historyMessages,
            toolSchemas,
            model,
        )
    }

    fun logAssistantRequestComplete(
        log: Logger,
        agent: String,
        mode: String,
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
            "LLM ← agent={} mode={} depth={} player={} model={} latencyMs={} {} tools=[{}] finish={} content=\"{}\"",
            agent,
            mode,
            depth,
            player ?: "?",
            model,
            latencyMs,
            usageLine,
            toolNames.joinToString(",").ifEmpty { "-" },
            finish,
            contentPreview,
        )
    }

    fun logRouterRequestStart(
        log: Logger,
        model: String,
        player: String?,
        message: String?,
        userChars: Int,
    ) {
        log.info(
            "LLM → router model={} player={} msg=\"{}\" userChars={}",
            model,
            player ?: "?",
            message?.take(80) ?: "",
            userChars,
        )
    }

    fun logRouterRequestComplete(
        log: Logger,
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
            "LLM ← router model={} player={} latencyMs={} {} finish={} content=\"{}\"",
            model,
            player ?: "?",
            latencyMs,
            usageLine,
            finish,
            contentPreview,
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
