package ru.arc.ai

import ru.arc.ai.memory.AssistantMemoryStore
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.routing.RoutingModule
import ru.arc.ai.tickets.ForumTicketSync
import ru.arc.ai.tickets.IssueTicketStore
import ru.arc.ai.tickets.TicketDialogStore
import ru.arc.config.ProxyConfigs
import ru.arc.config.Config
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object AssistantModule : PluginModule {
    override val name = "Assistant"
    override val priority = 85
    private const val REQUEST_THREADS = 16
    private val threadNumber = AtomicInteger()
    private var requestExecutor: ExecutorService? = null

    private fun executor(): ExecutorService {
        val current = requestExecutor
        if (current != null && !current.isShutdown) return current
        return Executors
            .newFixedThreadPool(REQUEST_THREADS) { runnable ->
                Thread(runnable, "proxyarc-assistant-${threadNumber.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }.also { requestExecutor = it }
    }

    override fun init() {
        val llmClient = Velocity.llmClient
        if (llmClient == null) {
            Velocity.logger?.warn("AssistantModule: LLM client not ready (NetworkModule must run first)")
            return
        }
        val proxy = Velocity.proxyServer
        if (proxy == null) {
            Velocity.logger?.warn("AssistantModule: proxy server not ready")
            return
        }
        val config = ProxyConfigs.module("assistant.yml")
        IssueTicketStore.bind(Velocity.redisManager)
        createAssistants(config, llmClient)
        RoutingModule.init(proxy, config, llmClient)
    }

    private fun createAssistants(
        config: Config,
        llmClient: OpenRouterLlmClient,
    ) {
        val storageKey = config.string("chat.memory.storage-key", AssistantMemoryStore.DEFAULT_STORAGE_KEY)
        Velocity.chatAssistant?.close()
        Velocity.chatAssistant =
            Assistant(
                config,
                "chat",
                llmClient,
                AssistantMemoryStore(Velocity.redisManager, storageKey),
                executor(),
            )
        // Keep tool calls, ticket details, and survey history out of public chat context.
        Velocity.bugSurveyAssistant?.close()
        Velocity.bugSurveyAssistant =
            Assistant(
                config,
                "bug-survey",
                llmClient,
                requestExecutor = executor(),
            )
    }

    override fun shutdown() {
        ForumTicketSync.stop()
        RoutingModule.shutdown()
        Velocity.chatAssistant?.close()
        Velocity.bugSurveyAssistant?.close()
        Velocity.chatAssistant = null
        Velocity.bugSurveyAssistant = null
        TicketDialogStore.clearAll()
        requestExecutor?.shutdownNow()
        requestExecutor = null
    }

    override fun reload() {
        val llmClient = Velocity.llmClient
        val proxy = Velocity.proxyServer
        if (llmClient == null || proxy == null) {
            val hadAssistant = Velocity.chatAssistant != null || Velocity.bugSurveyAssistant != null
            Velocity.chatAssistant?.reload()
            Velocity.bugSurveyAssistant?.reload()
            if (!hadAssistant) init()
            return
        }
        val config = ProxyConfigs.module("assistant.yml")
        RoutingModule.init(proxy, config, llmClient)
        createAssistants(config, llmClient)
    }
}
