package ru.arc.ai

import ru.arc.ai.memory.AssistantMemoryStore
import ru.arc.ai.routing.RoutingModule
import ru.arc.ai.tickets.ForumTicketSync
import ru.arc.ai.tickets.IssueTicketStore
import ru.arc.ai.tickets.TicketDialogStore
import ru.arc.config.ProxyConfigs
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity

object AssistantModule : PluginModule {
    override val name = "Assistant"
    override val priority = 85

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
        val storageKey = config.string("chat.memory.storage-key", AssistantMemoryStore.DEFAULT_STORAGE_KEY)
        Velocity.chatAssistant =
            Assistant(
                config,
                "chat",
                llmClient,
                AssistantMemoryStore(Velocity.redisManager, storageKey),
            )
        // Keep tool calls, ticket details, and survey history out of public chat context.
        Velocity.bugSurveyAssistant =
            Assistant(
                config,
                "bug-survey",
                llmClient,
            )
        RoutingModule.init(proxy, config, llmClient)
    }

    override fun shutdown() {
        ForumTicketSync.stop()
        RoutingModule.shutdown()
        Velocity.chatAssistant = null
        Velocity.bugSurveyAssistant = null
        TicketDialogStore.clearAll()
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
        val storageKey = config.string("chat.memory.storage-key", AssistantMemoryStore.DEFAULT_STORAGE_KEY)
        Velocity.chatAssistant?.reload()
            ?: run {
                Velocity.chatAssistant =
                    Assistant(
                        config,
                        "chat",
                        llmClient,
                        AssistantMemoryStore(Velocity.redisManager, storageKey),
                    )
            }
        Velocity.bugSurveyAssistant?.reload()
            ?: run {
                Velocity.bugSurveyAssistant =
                    Assistant(
                        config,
                        "bug-survey",
                        llmClient,
                    )
            }
    }
}
