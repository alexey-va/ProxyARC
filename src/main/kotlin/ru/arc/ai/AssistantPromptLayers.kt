package ru.arc.ai

import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import com.openai.models.completions.CompletionUsage
import org.slf4j.Logger
import ru.arc.ai.memory.AssistantMemoryStore
import ru.arc.ai.routing.survey.BugSurveySession
import ru.arc.ai.routing.survey.BugSurveySessionStore
import ru.arc.ai.tickets.IssueTicket
import ru.arc.ai.tickets.IssueTicketPromptFormat
import ru.arc.ai.tickets.PlayerWorldNames
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
        return triggerContextMessage(
            config = config,
            enabledKey = "chat.context.trigger-enabled",
            headerKey = "chat.context.trigger-header",
            defaultHeader = "текущее обращение:",
            player = player,
            message = message,
            server = server,
            messageName = TRIGGER_MESSAGE_NAME,
        )
    }

    fun bugTriggerContextMessage(
        config: Config,
        player: String?,
        message: String?,
        server: String?,
    ): ChatCompletionMessageParam? {
        val base =
            triggerContextMessage(
                config = config,
                enabledKey = "bug.context.trigger-enabled",
                headerKey = "bug.context.trigger-header",
                defaultHeader = "current message (bug):",
                player = player,
                message = message,
                server = server,
                messageName = TRIGGER_MESSAGE_NAME,
            ) ?: return null

        val session = player?.let { BugSurveySessionStore.findForPlayer(it) }
        val ticket =
            session?.ticketId
                ?: player?.let { IssueTicketStore.findOpenByReporter(it)?.ticketId }
        val hint = BugSurveyActionHint.turnHint(player, message, ticket) ?: return base

        val baseText = base.asUser().content().asText()
        return userMessage("$baseText\n\n$hint", TRIGGER_MESSAGE_NAME)
    }

    private fun triggerContextMessage(
        config: Config,
        enabledKey: String,
        headerKey: String,
        defaultHeader: String,
        player: String?,
        message: String?,
        server: String?,
        messageName: String,
    ): ChatCompletionMessageParam? {
        if (!config.bool(enabledKey, true)) return null
        if (player.isNullOrBlank() && message.isNullOrBlank()) return null
        val header = config.string(headerKey, defaultHeader)
        val body =
            buildString {
                player?.let { appendLine("player=$it") }
                val world =
                    PlayerWorldNames.resolveDisplay(
                        proxyOrHint = server,
                        messageText = message,
                    )
                if (world != "неизвестно") {
                    appendLine("world=$world")
                }
                message?.let { appendLine("message=$it") }
            }.trimEnd()
        if (body.isEmpty()) return null
        return userMessage("$header\n$body", messageName)
    }

    const val BUG_SURVEY_ACTIVE_MESSAGE_NAME = "bug-survey-active"

    fun chatBugSurveyActiveMessage(player: String?): ChatCompletionMessageParam? {
        val name = player?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val session = BugSurveySessionStore.get(name) ?: return null
        val ticket = session.ticketId ?: "без тикета"
        val topic = session.topicHint ?: "баг"
        return userMessage(
            "active bug-survey: $topic ($ticket) — do not create duplicate tickets; survey agent handles PM",
            BUG_SURVEY_ACTIVE_MESSAGE_NAME,
        )
    }

    fun bugRecentOpenTicketsMessage(config: Config): ChatCompletionMessageParam? {
        if (!config.bool("bug.context.recent-tickets-enabled", true)) return null
        val limit = config.integer("bug.context.recent-tickets", 3).coerceIn(1, 20)
        val tickets = IssueTicketStore.listOpenRecent(limit)
        if (tickets.isEmpty()) return null
        val header =
            config.string(
                "bug.context.recent-tickets-header",
                "open forum tickets (top $limit):",
            )
        val body = tickets.joinToString("\n") { IssueTicketPromptFormat.formatListLine(it) }
        return userMessage("$header\n$body", RECENT_OPEN_TICKETS_MESSAGE_NAME)
    }

    fun bugInvestigationScopeMessage(reporter: String?): ChatCompletionMessageParam? {
        val session = reporter?.let { BugSurveySessionStore.findForPlayer(it) } ?: return null
        val body =
            buildString {
                appendLine("bug investigation (multi-player ok):")
                appendLine("reporter=${session.player}")
                session.ticketId?.let { appendLine("ticket=$it") }
                if (session.participants.size > 1) {
                    appendLine("participants=${session.participants.joinToString()}")
                }
                if (session.awaitingGlobalResponses) {
                    appendLine("awaiting replies after sendglobalmessage")
                    session.lastGlobalQuestion?.let { appendLine("question=$it") }
                }
                append(
                    "expand: sendglobalmessage ask online; updateissueticket log witnesses; " +
                        "sendprivatemessage clarify one player.",
                )
            }
        return userMessage(body, "bug-investigation-scope")
    }

    fun bugOpenTicketMessage(
        config: Config,
        reporter: String?,
    ): ChatCompletionMessageParam? {
        if (!config.bool("bug.context.open-ticket-enabled", true)) return null
        val player = reporter?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val session = BugSurveySessionStore.findForPlayer(player)
        val open =
            session?.ticketId?.let { IssueTicketStore.find(it) }
                ?: IssueTicketStore.findOpenByReporter(player)
                ?: return null
        val header =
            config.string(
                "bug.context.open-ticket-header",
                "reporter open_ticket:",
            )
        return userMessage(
            "$header\n${IssueTicketPromptFormat.formatOpenTicketBlock(open)}\n" +
                participantHint(session) +
                "действие: если репорт без деталей — sendprivatemessage (что сломалось, команда, мир: спавн/мир биомов/ванильный/данжи). " +
                "SKIP без tools запрещено. " +
                "не говори игроку смотреть тикет — тикеты на форуме разбирает грос (ник грос).",
            OPEN_TICKET_MESSAGE_NAME,
        )
    }

    private fun participantHint(session: BugSurveySession?): String {
        if (session == null || session.participants.size <= 1) return ""
        return "participants: ${session.participants.joinToString()}. "
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
                "recent global chat (report context):",
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
        log.debug(
            "LLM usage model={} {}",
            model,
            ru.arc.ai.llm.LlmRequestLogger.formatUsage(usage),
        )
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
