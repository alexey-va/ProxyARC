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
import java.util.Optional
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
    var currentRequest: CompletableFuture<Optional<String>>? = null

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

    fun tryEnqueue(): CompletableFuture<Optional<String>> {
        if (!config.bool("$type.enabled", true)) {
            return CompletableFuture.completedFuture(Optional.empty())
        }
        if (!llmClient.enabled || client == null) {
            log.info("Assistant client is not initialized, check modules/llm.yml api-key")
            return CompletableFuture.completedFuture(Optional.empty())
        }
        if (System.currentTimeMillis() < leftConversationUntil) {
            log.info("Assistant of type {} is currently away until {}", type, Date(leftConversationUntil))
            return CompletableFuture.completedFuture(Optional.empty())
        }
        if (currentRequest != null && currentRequest!!.isDone) {
            currentRequest = null
        }
        if (currentRequest != null) {
            return CompletableFuture.completedFuture(Optional.empty())
        }
        currentRequest = sendRequest(0)
        return currentRequest!!
    }

    private fun sendRequest(depth: Int): CompletableFuture<Optional<String>> {
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
                    sendRequest(depth + 1).join()
                } else {
                    val messageContent = message.content().orElse("")
                    if (messageContent.equals("пропускаю", ignoreCase = true) || messageContent.isBlank()) {
                        Optional.empty()
                    } else {
                        Optional.of(messageContent)
                    }
                }
            } catch (e: Exception) {
                log.error("Error during chat completion", e)
                Optional.empty()
            }
        }
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
