package ru.arc.ai

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

    fun logSummary(log: org.slf4j.Logger, assistantType: String) {
        if (hasReply) {
            log.info(
                "Assistant [{}] reply to {}: raw=\"{}\" delivered=\"{}\"",
                assistantType,
                triggerPlayer ?: "?",
                rawModelContent ?: reply,
                reply,
            )
            return
        }
        log.info(
            "Assistant [{}] skip for {} on \"{}\": reason={} ({}) raw=\"{}\"{}",
            assistantType,
            triggerPlayer ?: "?",
            triggerMessage,
            skipReason?.code ?: "unknown",
            skipReason?.description ?: "unknown",
            rawModelContent,
            detail?.let { " detail=$it" }.orEmpty(),
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
    MODEL_SKIP("model_skip", "model returned пропускаю"),
    MODEL_BLANK("model_blank", "model returned empty or whitespace-only content"),
    MODEL_TOOL_ONLY("model_tool_only", "model returned only tool calls without user-visible text"),
    POST_FILTER("post_filter", "reply dropped by chat post-processing"),
    LLM_ERROR("llm_error", "LLM request failed"),
}
