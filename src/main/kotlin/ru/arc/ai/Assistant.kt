package ru.arc.ai

import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import org.slf4j.LoggerFactory
import ru.arc.ai.Defaults.DEFAULT_SYSTEM
import ru.arc.ai.memory.AssistantHistoryCompactor
import ru.arc.ai.memory.AssistantMemoryStore
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.tools.RemoteToolSupport
import ru.arc.ai.tools.Tool
import ru.arc.ai.tools.Tools
import ru.arc.config.Config
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import java.util.Deque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque

class Assistant(
    private val config: Config,
    private val type: String,
    private val llmClient: OpenRouterLlmClient,
    val memoryStore: AssistantMemoryStore = AssistantMemoryStore(null),
) {
    private val log = LoggerFactory.getLogger(Assistant::class.java)

    private var prompt: String = DEFAULT_SYSTEM
    private var bugPrompt: String = DEFAULT_SYSTEM
    private var leftConversationUntil: Long = 0
    private val history: Deque<ChatCompletionMessageParam> = ConcurrentLinkedDeque()
    private val historyLabels: Deque<String> = ConcurrentLinkedDeque()
    private val chatObservations: Deque<String> = ConcurrentLinkedDeque()
    var currentRequest: CompletableFuture<AssistantEnqueueResult>? = null
    var lastTriggerPlayer: String? = null
        private set
    var lastTriggerMessage: String? = null
        private set
    var lastTriggerServer: String? = null
        private set

    private val client: OpenAIClient?
        get() = llmClient.client

    init {
        loadPrompt()
        loadBugPrompt()
        assistants.add(this)
    }

    fun reload() {
        loadPrompt()
        loadBugPrompt()
        history.clear()
        historyLabels.clear()
        chatObservations.clear()
        currentRequest = null
        leftConversationUntil = 0
    }

    fun snapshotChatObservations(maxLines: Int = 10): String {
        if (chatObservations.isEmpty()) return ""
        val lines = chatObservations.toList().takeLast(maxLines.coerceAtLeast(1))
        return lines.joinToString("\n")
    }

    fun observeChat(formattedLine: String) {
        if (formattedLine.isBlank()) return
        chatObservations.addLast(formattedLine.trim())
        val maxLines = config.integer("$type.observe-max-lines", 40)
        AssistantHistoryCompactor.compactObservations(chatObservations, maxLines)
    }

    private fun systemPrompt(mode: AssistantRunMode): String =
        when (mode) {
            AssistantRunMode.CHAT -> prompt
            AssistantRunMode.BUG -> bugPrompt
        }

    private val tools: Collection<Class<out Tool>>
        get() = tools(AssistantRunMode.CHAT)

    private fun tools(mode: AssistantRunMode): Collection<Class<out Tool>> {
        val section = mode.configSection()
        val defaults =
            when (mode) {
                AssistantRunMode.CHAT -> emptyList()
                AssistantRunMode.BUG ->
                    listOf(
                        "createissueticket",
                        "updateissueticket",
                        "sendprivatemessage",
                        "listissuetickets",
                    )
            }
        val toolNames =
            config.stringList("$section.tools", defaults)
                .map { it.lowercase() }
                .toSet()
        return Tools.getAllTools()
            .filter { toolNames.contains(it.simpleName.lowercase()) }
            .toSet()
    }

    private fun isModeEnabled(mode: AssistantRunMode): Boolean =
        config.bool("${mode.configSection()}.enabled", true)

    private fun loadBugPrompt() {
        bugPrompt = loadPromptFile("bug-ticket", DEFAULT_SYSTEM.trimIndent())
    }

    private fun loadPrompt() {
        prompt = loadPromptFile(type, DEFAULT_SYSTEM.trimIndent())
    }

    private fun loadPromptFile(
        promptType: String,
        fallback: String,
    ): String {
        try {
            val promptFolder = config.dataFolder.toPath().resolve("prompts")
            if (!Files.exists(promptFolder)) {
                Files.createDirectories(promptFolder)
            }
            val promptPath = promptFolder.resolve("$promptType.txt")
            if (!Files.exists(promptPath)) {
                copyBundledPrompt(promptPath, promptType)
            }
            if (!Files.exists(promptPath)) {
                return fallback
            }
            val text = Files.readString(promptPath).trim()
            return if (text.isEmpty()) fallback else text
        } catch (e: Exception) {
            log.info("Error reading prompt file {}", promptType, e)
            return fallback
        }
    }

    private fun copyBundledPrompt(promptPath: Path, type: String) {
        Assistant::class.java.getResourceAsStream("/prompts/$type.txt")?.use { input ->
            Files.copy(input, promptPath)
        }
    }

    fun leaveForTime(minutes: Int) {
        log.info("Assistant of type {} is leaving for {} minutes", type, minutes)
        leftConversationUntil = System.currentTimeMillis() + minutes.toLong() * 60 * 1000
    }

    fun addChatMessage(
        content: String,
        player: String,
    ) {
        history.addLast(
            ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder()
                    .content(content)
                    .name(player)
                    .build(),
            ),
        )
        historyLabels.addLast(content)
        trimAndCompactHistory()
    }

    private fun trimAndCompactHistory() {
        val maxHistory = config.integer("$type.max-history", 20)
        while (history.size > maxHistory) {
            history.pollFirst()
            historyLabels.pollFirst()
        }
        val threshold = config.integer("$type.compact-threshold", maxHistory)
        val keepRecent = config.integer("$type.compact-keep-recent", maxHistory / 2)
        AssistantHistoryCompactor.compactHistory(history, historyLabels, threshold, keepRecent)
    }

    fun tryEnqueue(
        triggerPlayer: String? = null,
        triggerMessage: String? = null,
        mode: AssistantRunMode = AssistantRunMode.CHAT,
        triggerServer: String? = null,
    ): CompletableFuture<AssistantEnqueueResult> {
        if (!isModeEnabled(mode)) {
            return completedSkip(SkipReason.DISABLED, triggerPlayer, triggerMessage)
        }
        if (!llmClient.enabled || client == null) {
            return completedSkip(SkipReason.LLM_NOT_READY, triggerPlayer, triggerMessage)
        }
        if (System.currentTimeMillis() < leftConversationUntil) {
            return completedSkip(
                SkipReason.AWAY,
                triggerPlayer,
                triggerMessage,
                detail = "until ${Date(leftConversationUntil)}",
            )
        }
        if (currentRequest != null && currentRequest!!.isDone) {
            currentRequest = null
        }
        if (currentRequest != null) {
            log.debug(
                "Assistant [{}] skip for {} on \"{}\": reason={} ({})",
                type,
                triggerPlayer ?: "?",
                triggerMessage,
                SkipReason.BUSY.code,
                SkipReason.BUSY.description,
            )
            return completedSkip(SkipReason.BUSY, triggerPlayer, triggerMessage)
        }
        lastTriggerPlayer = triggerPlayer
        lastTriggerMessage = triggerMessage
        lastTriggerServer = triggerServer?.trim()?.takeIf { it.isNotEmpty() }
        currentRequest = sendRequest(0, triggerPlayer, triggerMessage, mode)
        return currentRequest!!
    }

    private fun completedSkip(
        reason: SkipReason,
        triggerPlayer: String?,
        triggerMessage: String?,
        raw: String? = null,
        detail: String? = null,
    ): CompletableFuture<AssistantEnqueueResult> {
        val result =
            AssistantEnqueueResult.skip(
                reason = reason,
                raw = raw,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = detail,
            )
        result.logSummary(log, type)
        return CompletableFuture.completedFuture(result)
    }

    private fun sendRequest(
        depth: Int,
        triggerPlayer: String?,
        triggerMessage: String?,
        mode: AssistantRunMode,
    ): CompletableFuture<AssistantEnqueueResult> {
        val runModel = model(mode)
        val builder =
            ChatCompletionCreateParams.builder()
                .addSystemMessage(AssistantPromptLayers.staticSystemPrompt(systemPrompt(mode)))
                .temperature(temperature(mode))
                .model(runModel)
        if (mode == AssistantRunMode.CHAT) {
            AssistantPromptLayers.memoryContextMessage(config, type, memoryStore)?.let { builder.addMessage(it) }
            AssistantPromptLayers.chatContextMessage(config, type, chatObservations)?.let { builder.addMessage(it) }
            AssistantPromptLayers.chatTriggerContextMessage(
                config,
                triggerPlayer,
                triggerMessage,
                lastTriggerServer,
            )?.let { builder.addMessage(it) }
        } else if (mode == AssistantRunMode.BUG) {
            AssistantPromptLayers.bugRecentOpenTicketsMessage(config)?.let { builder.addMessage(it) }
            AssistantPromptLayers.bugOpenTicketMessage(config, triggerPlayer)?.let { builder.addMessage(it) }
            AssistantPromptLayers.bugRecentChatMessage(config, type, chatObservations)?.let { builder.addMessage(it) }
        }
        for (message in history) {
            builder.addMessage(message)
        }
        for (tool in tools(mode)) {
            builder.addTool(tool)
        }
        val params = builder.build()

        return CompletableFuture.supplyAsync {
            try {
                val response = client!!.chat().completions().create(params)
                AssistantPromptLayers.logCompletionUsage(log, runModel, response)
                val message = response.choices().first().message()
                history.addLast(ChatCompletionMessageParam.ofAssistant(message.toParam()))
                historyLabels.addLast("бот: ${message.content().orElse("")}")
                val toolCalls = message.toolCalls().orElse(emptyList())
                toolCalls.forEach { toolCall ->
                    val result = executeTool(toolCall)
                    history.addLast(
                        ChatCompletionMessageParam.ofTool(
                            ChatCompletionToolMessageParam.builder()
                                .toolCallId(toolCall.asFunction().id())
                                .contentAsJson(result)
                                .build(),
                        ),
                    )
                    historyLabels.addLast("tool:${toolCall.asFunction().function().name()}")
                }
                if (toolCalls.isNotEmpty() && depth < 3) {
                    sendRequest(depth + 1, triggerPlayer, triggerMessage, mode).join()
                } else {
                    evaluateModelContent(
                        raw = message.content().orElse(""),
                        hadToolCalls = toolCalls.isNotEmpty(),
                        depth = depth,
                        triggerPlayer = triggerPlayer,
                        triggerMessage = triggerMessage,
                        mode = mode,
                    )
                }
            } catch (e: Exception) {
                log.error(
                    "Assistant [{}] LLM error for {} on \"{}\": {}",
                    type,
                    triggerPlayer ?: "?",
                    triggerMessage,
                    e.message,
                    e,
                )
                AssistantEnqueueResult.skip(
                    reason = SkipReason.LLM_ERROR,
                    triggerPlayer = triggerPlayer,
                    triggerMessage = triggerMessage,
                    detail = e.message,
                ).also { it.logSummary(log, type) }
            }
        }
    }

    private fun evaluateModelContent(
        raw: String,
        hadToolCalls: Boolean,
        depth: Int,
        triggerPlayer: String?,
        triggerMessage: String?,
        mode: AssistantRunMode,
    ): AssistantEnqueueResult {
        val trimmed = raw.trim()
        if (trimmed.equals("пропускаю", ignoreCase = true)) {
            return AssistantEnqueueResult.skip(
                reason = SkipReason.MODEL_SKIP,
                raw = raw,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
            ).also { it.logSummary(log, type) }
        }
        if (trimmed.isEmpty()) {
            val reason =
                if (hadToolCalls) SkipReason.MODEL_TOOL_ONLY else SkipReason.MODEL_BLANK
            return AssistantEnqueueResult.skip(
                reason = reason,
                raw = raw,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = if (hadToolCalls) "tool round depth=$depth" else null,
            ).also { it.logSummary(log, type) }
        }
        if (mode == AssistantRunMode.BUG) {
            return AssistantEnqueueResult.skip(
                reason = SkipReason.MODEL_SKIP,
                raw = raw,
                triggerPlayer = triggerPlayer,
                triggerMessage = triggerMessage,
                detail = "bug_mode_public_reply_blocked",
            ).also {
                log.info(
                    "Bug agent blocked public reply for {}: {}",
                    triggerPlayer ?: "?",
                    trimmed.take(120),
                )
            }
        }
        return AssistantEnqueueResult.reply(
            text = raw,
            raw = raw,
            triggerPlayer = triggerPlayer,
            triggerMessage = triggerMessage,
        ).also { it.logSummary(log, type) }
    }

    private fun model(mode: AssistantRunMode): String {
        val section = mode.configSection()
        val chatDefault = config.string("chat.model", "x-ai/grok-4-fast:free")
        return config.string("$section.model", chatDefault)
    }

    private fun temperature(mode: AssistantRunMode): Double {
        val section = mode.configSection()
        val chatDefault = config.real("chat.temperature", 0.7)
        return config.real("$section.temperature", chatDefault)
    }

    private fun executeTool(toolCall: ChatCompletionMessageToolCall): Any {
        val toolClass = Tools.getTool(toolCall.asFunction().function().name())
        if (toolClass == null) {
            return "Unknown tool: ${toolCall.asFunction().function().name()}"
        }
        return try {
            val tool = toolCall.asFunction().function().arguments(toolClass)
            log.info("Executing tool {}", tool)
            if (tool is RemoteToolSupport) {
                tool.executeRemote().join()
            } else {
                tool.execute(this) ?: ""
            }
        } catch (e: Exception) {
            "Error executing tool: ${e.message}"
        }
    }

    companion object {
        @JvmField
        val assistants: MutableList<Assistant> = ArrayList()
    }
}
