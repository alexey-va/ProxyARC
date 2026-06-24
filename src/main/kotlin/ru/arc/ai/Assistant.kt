package ru.arc.ai

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import org.slf4j.LoggerFactory
import ru.arc.ai.Defaults.DEFAULT_SYSTEM
import ru.arc.ai.tools.Tool
import ru.arc.ai.tools.Tools
import ru.arc.config.Config
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Deque
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque

class Assistant(
    private val config: Config,
    private val type: String,
) {
    private val log = LoggerFactory.getLogger(Assistant::class.java)

    private var apiBaseUrl: String = config.string("api-base-url", "https://openrouter.ai/api/v1")
    private var client: OpenAIClient? = null
    private var prompt: String = DEFAULT_SYSTEM
    private var leftConversationUntil: Long = 0
    private val history: Deque<ChatCompletionMessageParam> = ConcurrentLinkedDeque()
    var currentRequest: CompletableFuture<Optional<String>>? = null

    init {
        updateClient()
        loadPrompt()
        assistants.add(this)
    }

    fun reload() {
        updateClient()
        loadPrompt()
        history.clear()
        currentRequest = null
        leftConversationUntil = 0
    }

    private fun loadPrompt() {
        try {
            val promptFolder = config.folder.resolve("prompts")
            if (!Files.exists(promptFolder)) {
                Files.createDirectories(promptFolder)
            }
            val promptPath = promptFolder.resolve("$type.txt")
            if (!Files.exists(promptPath)) {
                Files.createFile(promptPath)
                Files.writeString(promptPath, DEFAULT_SYSTEM)
                prompt = DEFAULT_SYSTEM
            } else {
                prompt = String(Files.readAllBytes(promptPath))
            }
        } catch (e: Exception) {
            log.info("Error reading prompt file", e)
            prompt = DEFAULT_SYSTEM
        }
    }

    fun leaveForTime(minutes: Int) {
        log.info("Assistant of type {} is leaving for {} minutes", type, minutes)
        leftConversationUntil = System.currentTimeMillis() + minutes.toLong() * 60 * 1000
    }

    private fun updateClient() {
        val apiKey = config.string("api-key", "none")
        if (apiKey == "none") {
            log.error("API key is not set for assistant type {}", type)
            client = null
        } else {
            client = createClient(apiKey)
        }
    }

    private fun createClient(apiKey: String): OpenAIClient {
        val proxyEnabled = config.bool("proxy.enabled", true)
        val proxyHost = config.string("proxy.host", "127.0.0.1")
        val proxyPort = config.integer("proxy.port", 8888)
        val builder = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.of(10, ChronoUnit.SECONDS))
            .baseUrl(apiBaseUrl)
        if (proxyEnabled) {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
            builder.proxy(proxy)
        }
        return builder.build()
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
            log.info("Trimming chat history, size: {}", history.size)
            history.pollFirst()
        }
    }

    fun tryEnqueue(): CompletableFuture<Optional<String>> {
        if (!config.bool("$type.enabled", true)) {
            log.info("Assistant of type {} is disabled", type)
            return CompletableFuture.completedFuture(Optional.empty())
        }
        if (client == null) {
            updateClient()
            if (client == null) {
                log.info("Assistant client is not initialized, check API key")
                return CompletableFuture.completedFuture(Optional.empty())
            }
        }
        if (System.currentTimeMillis() < leftConversationUntil) {
            log.info("Assistant of type {} is currently away until {}", type, Date(leftConversationUntil))
            return CompletableFuture.completedFuture(Optional.empty())
        }
        if (currentRequest != null && currentRequest!!.isDone) {
            currentRequest = null
        }
        if (currentRequest != null) {
            log.info("Request already in progress")
            return CompletableFuture.completedFuture(Optional.empty())
        }
        currentRequest = sendRequest(0)
        return currentRequest!!
    }

    private fun sendRequest(depth: Int): CompletableFuture<Optional<String>> {
        val builder = ChatCompletionCreateParams.builder()
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
                    log.info("Tool calls detected, sending follow-up request")
                    sendRequest(depth + 1).join()
                } else {
                    log.info("No tool calls, returning response\n{}", message.content().orElse(""))
                    val messageContent = message.content().orElse("")
                    if (messageContent.equals("пропускаю", ignoreCase = true)) {
                        Optional.empty()
                    } else if (messageContent.trim().isEmpty()) {
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

    private val systemMessage: String
        get() = prompt

    private val model: String
        get() = config.string("$type.model", "x-ai/grok-4-fast:free")

    private val temperature: Double
        get() = config.real("$type.temperature", 0.7)

    private val tools: Collection<Class<out Tool>>
        get() {
            val toolNames = config.stringList("$type.tools", emptyList())
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
            tool.execute(this) ?: ""
        } catch (e: Exception) {
            "Error executing tool: ${e.message}"
        }
    }

    companion object {
        @JvmField
        val assistants: MutableList<Assistant> = ArrayList()
    }
}
