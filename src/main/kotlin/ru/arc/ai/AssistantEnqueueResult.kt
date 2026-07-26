package ru.arc.ai

import ru.arc.ai.llm.LogPreview

/**
 * Result of a chat assistant enqueue / LLM call.
 * Always carries [skipReason] when [reply] is null.
 */
data class AssistantEnqueueResult(
    val reply: String? = null,
    val skipReason: SkipReason? = null,
    val rawModelContent: String? = null,
    val triggerPlayer: String? = null,
    val triggerMessage: String? = null,
    val detail: String? = null,
) {
    val hasReply: Boolean get() = !reply.isNullOrBlank()

    fun logSummary(log: org.slf4j.Logger, assistantType: String, mode: AssistantRunMode? = null) {
        val modeLabel = mode?.name?.lowercase()
        val tag = if (modeLabel != null) "$assistantType/$modeLabel" else assistantType
        if (hasReply) {
            log.info(
                "Assistant [{}] reply to {}: raw=\"{}\" delivered=\"{}\"",
                tag,
                triggerPlayer ?: "?",
                LogPreview.of(rawModelContent ?: reply),
                LogPreview.of(reply),
            )
            return
        }
        log.info(
            "Assistant [{}] skip for {} on \"{}\": reason={} ({}) raw=\"{}\"{}",
            tag,
            triggerPlayer ?: "?",
            LogPreview.of(triggerMessage, 160),
            skipReason?.code ?: "unknown",
            skipReason?.description ?: "unknown",
            LogPreview.of(rawModelContent),
            detail?.let { " detail=${LogPreview.of(it, 160)}" }.orEmpty(),
        )
    }

    companion object {
        fun reply(
            text: String,
            raw: String? = null,
            triggerPlayer: String? = null,
            triggerMessage: String? = null,
        ): AssistantEnqueueResult =
            AssistantEnqueueResult(
                reply = text,
                rawModelContent = raw ?: text,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
            )

        fun skip(
            reason: SkipReason,
            raw: String? = null,
            triggerPlayer: String? = null,
            triggerMessage: String? = null,
            detail: String? = null,
        ): AssistantEnqueueResult =
            AssistantEnqueueResult(
                skipReason = reason,
                rawModelContent = raw,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = detail,
            )
    }
}

enum class SkipReason(val code: String, val description: String) {
    DISABLED("disabled", "assistant disabled in config"),
    LLM_NOT_READY("llm_not_ready", "LLM client not initialized — check modules/llm.yml api-key"),
    AWAY("away", "assistant temporarily away (LeaveForTime)"),
    BUSY("busy", "previous LLM request still running"),
    MODEL_SKIP("model_skip", "model returned SKIP"),
    MODEL_BLANK("model_blank", "model returned empty or whitespace-only content"),
    MODEL_TOOL_ONLY("model_tool_only", "model returned only tool calls without user-visible text"),
    POST_FILTER("post_filter", "reply dropped by chat post-processing"),
    LLM_ERROR("llm_error", "LLM request failed"),
}
