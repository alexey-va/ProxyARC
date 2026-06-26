package ru.arc.ai

import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import org.slf4j.LoggerFactory
import ru.arc.ai.Defaults.DEFAULT_SYSTEM
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
) {
    private val log = LoggerFactory.getLogger(Assistant::class.java)

    private var prompt: String = DEFAULT_SYSTEM
    private var leftConversationUntil: Long = 0
    private val history: Deque<ChatCompletionMessageParam> = ConcurrentLinkedDeque()
    var currentRequest: CompletableFuture<AssistantEnqueueResult>? = null

    private val client: OpenAIClient?
        get() = llmClient.client

    init {
        loadPrompt()
        assistants.add(this)
    }

    fun reload() {
        loadPrompt()
        history.clear()
        currentRequest = null
        leftConversationUntil = 0
    }

    private fun loadPrompt() {
        try {
            val promptFolder = config.dataFolder.toPath().resolve("prompts")
            if (!Files.exists(promptFolder)) {
                Files.createDirectories(promptFolder)
            }
            val promptPath = promptFolder.resolve("$type.txt")
            if (!Files.exists(promptPath)) {
                copyBundledPrompt(promptPath, type)
            }
            if (!Files.exists(promptPath)) {
                Files.createFile(promptPath)
                Files.writeString(promptPath, DEFAULT_SYSTEM.trimIndent())
            }
            prompt = Files.readString(promptPath)
        } catch (e: Exception) {
            log.info("Error reading prompt file", e)
            prompt = DEFAULT_SYSTEM.trimIndent()
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

    fun addChatMessage(message: String, player: String) {
        history.addLast(
            ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder()
                    .content(message)
                    .name(player)
                    .build(),
            ),
        )
        val maxHistory = config.integer("$type.max-history", 20)
        while (history.size > maxHistory) {
            history.pollFirst()
        }
    }

    fun tryEnqueue(
        triggerPlayer: String? = null,
        triggerMessage: String? = null,
    ): CompletableFuture<AssistantEnqueueResult> {
        if (!config.bool("$type.enabled", true)) {
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
        currentRequest = sendRequest(0, triggerPlayer, triggerMessage)
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
    ): CompletableFuture<AssistantEnqueueResult> {
        val builder =
            ChatCompletionCreateParams.builder()
                .addSystemMessage(systemMessage)
                .temperature(temperature)
                .model(model)
        for (message in history) {
            builder.addMessage(message)
        }
        for (tool in tools) {
            builder.addTool(tool)
        }
        val params = builder.build()

        return CompletableFuture.supplyAsync {
            try {
                val response = client!!.chat().completions().create(params)
                val message = response.choices().first().message()
                history.addLast(ChatCompletionMessageParam.ofAssistant(message.toParam()))
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
                }
                if (toolCalls.isNotEmpty() && depth < 3) {
                    sendRequest(depth + 1, triggerPlayer, triggerMessage).join()
                } else {
                    evaluateModelContent(
                        raw = message.content().orElse(""),
                        hadToolCalls = toolCalls.isNotEmpty(),
                        depth = depth,
                        triggerPlayer = triggerPlayer,
                        triggerMessage = triggerMessage,
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
        return AssistantEnqueueResult.reply(
            text = raw,
            raw = raw,
            triggerPlayer = triggerPlayer,
            triggerMessage = triggerMessage,
        ).also { it.logSummary(log, type) }
    }

    private val systemMessage: String get() = prompt

    private val model: String get() = config.string("$type.model", "x-ai/grok-4-fast:free")

    private val temperature: Double get() = config.real("$type.temperature", 0.7)

    private val tools: Collection<Class<out Tool>>
        get() {
            val toolNames =
                config.stringList("$type.tools", emptyList())
                    .map { it.lowercase() }
                    .toSet()
            return Tools.getAllTools()
                .filter { toolNames.contains(it.simpleName.lowercase()) }
                .toSet()
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
