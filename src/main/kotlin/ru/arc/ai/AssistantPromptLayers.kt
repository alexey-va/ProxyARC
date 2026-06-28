package ru.arc.ai

import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.completions.CompletionUsage
import org.slf4j.Logger
import ru.arc.ai.memory.AssistantMemoryStore
import ru.arc.ai.tickets.IssueTicket
import ru.arc.ai.tickets.IssueTicketPromptFormat
import ru.arc.ai.tickets.IssueTicketStore
import ru.arc.config.Config

/**
 * Splits assistant context into cache-friendly layers for DeepSeek / OpenRouter.
 *
 * Static [staticSystemPrompt] is identical on every request so provider KV cache can hit.
 * Dynamic memory and chat observations go into separate user messages after system.
 */
object AssistantPromptLayers {
    const val MEMORY_MESSAGE_NAME = "agent-memory"
    const val CHAT_LOG_MESSAGE_NAME = "chat-log"
    const val TRIGGER_MESSAGE_NAME = "trigger"
    const val RECENT_OPEN_TICKETS_MESSAGE_NAME = "recent-open-tickets"
    const val OPEN_TICKET_MESSAGE_NAME = "open-ticket"
    const val BUG_CHAT_MESSAGE_NAME = "bug-chat-context"

    fun staticSystemPrompt(prompt: String): String = prompt.trim()

    fun memoryContextMessage(
        config: Config,
        type: String,
        memoryStore: AssistantMemoryStore,
    ): ChatCompletionMessageParam? {
        if (!config.bool("$type.memory.enabled", true)) return null
        val minConf = config.real("$type.memory.min-confidence", 0.5)
        val maxFacts = config.integer("$type.memory.max-injected", 20)
        val block = memoryStore.formatForPrompt(minConf, maxFacts) ?: return null
        val header =
            config.string(
                "$type.memory.prompt-header",
                "запомненные факты (rememberfact/forgetfact):",
            )
        return userMessage("$header\n$block", MEMORY_MESSAGE_NAME)
    }

    fun chatContextMessage(
        config: Config,
        type: String,
        chatObservations: Collection<String>,
    ): ChatCompletionMessageParam? {
        if (!config.bool("$type.observe-all-chat", true)) return null
        if (chatObservations.isEmpty()) return null
        val header =
            config.string(
                "$type.observe-context-header",
                "контекст чата (время, +Xs после скорена, флаги [→скорен] [ответ скорену] [к ник]):",
            )
        val body = chatObservations.joinToString("\n") { "- $it" }
        return userMessage("$header\n$body", CHAT_LOG_MESSAGE_NAME)
    }

    fun chatTriggerContextMessage(
        config: Config,
        player: String?,
        message: String?,
        server: String?,
    ): ChatCompletionMessageParam? {
        if (!config.bool("chat.context.trigger-enabled", true)) return null
        if (player.isNullOrBlank() && message.isNullOrBlank()) return null
        val header =
            config.string(
                "chat.context.trigger-header",
                "текущее обращение:",
            )
        val body =
            buildString {
                player?.let { appendLine("player=$it") }
                server?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("server=$it") }
                message?.let { appendLine("message=$it") }
            }.trimEnd()
        if (body.isEmpty()) return null
        return userMessage("$header\n$body", TRIGGER_MESSAGE_NAME)
    }

    fun bugRecentOpenTicketsMessage(config: Config): ChatCompletionMessageParam? {
        if (!config.bool("bug.context.recent-tickets-enabled", true)) return null
        val limit = config.integer("bug.context.recent-tickets", 10).coerceIn(1, 20)
        val tickets = IssueTicketStore.listOpenRecent(limit)
        if (tickets.isEmpty()) return null
        val header =
            config.string(
                "bug.context.recent-tickets-header",
                "открытые тикеты (форум, top $limit):",
            )
        val body = tickets.joinToString("\n") { IssueTicketPromptFormat.formatListLine(it) }
        return userMessage("$header\n$body", RECENT_OPEN_TICKETS_MESSAGE_NAME)
    }

    fun bugOpenTicketMessage(
        config: Config,
        reporter: String?,
    ): ChatCompletionMessageParam? {
        if (!config.bool("bug.context.open-ticket-enabled", true)) return null
        val player = reporter?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val open = IssueTicketStore.findOpenByReporter(player) ?: return null
        val header =
            config.string(
                "bug.context.open-ticket-header",
                "open_ticket репортёра:",
            )
        return userMessage(
            "$header\n${IssueTicketPromptFormat.formatOpenTicketBlock(open)}",
            OPEN_TICKET_MESSAGE_NAME,
        )
    }

    fun bugRecentChatMessage(
        config: Config,
        type: String,
        chatObservations: Collection<String>,
    ): ChatCompletionMessageParam? {
        if (!config.bool("bug.context.recent-chat-enabled", true)) return null
        if (chatObservations.isEmpty()) return null
        val maxLines = config.integer("bug.context.recent-chat-lines", 8).coerceIn(1, 20)
        val lines = chatObservations.toList().takeLast(maxLines)
        val header =
            config.string(
                "bug.context.recent-chat-header",
                "недавний глобальный чат (контекст репорта):",
            )
        val body = lines.joinToString("\n") { "- $it" }
        return userMessage("$header\n$body", BUG_CHAT_MESSAGE_NAME)
    }

    fun logCompletionUsage(
        log: Logger,
        model: String,
        response: ChatCompletion,
    ) {
        response.usage().ifPresent { usage ->
            logCompletionUsage(log, model, usage)
        }
    }

    internal fun logCompletionUsage(
        log: Logger,
        model: String,
        usage: CompletionUsage,
    ) {
        val promptTokens = usage.promptTokens()
        val completionTokens = usage.completionTokens()
        val cachedTokens =
            usage.promptTokensDetails().flatMap { it.cachedTokens() }.orElse(0L)
        if (cachedTokens > 0L) {
            val pct =
                if (promptTokens > 0L) {
                    (cachedTokens * 100 / promptTokens)
                } else {
                    0L
                }
            log.info(
                "LLM cache hit model={} cached={}/{} prompt tokens ({}%) completion={}",
                model,
                cachedTokens,
                promptTokens,
                pct,
                completionTokens,
            )
        } else {
            log.debug(
                "LLM usage model={} prompt={} completion={} (no cache hit yet)",
                model,
                promptTokens,
                completionTokens,
            )
        }
    }

    private fun userMessage(
        content: String,
        name: String,
    ): ChatCompletionMessageParam =
        ChatCompletionMessageParam.ofUser(
            ChatCompletionUserMessageParam.builder()
                .content(content)
                .name(name)
                .build(),
        )
}
