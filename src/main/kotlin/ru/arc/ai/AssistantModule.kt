package ru.arc.ai

import ru.arc.ai.memory.AssistantMemoryStore
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
        val config = ProxyConfigs.module("assistant.yml")
        val storageKey = config.string("chat.memory.storage-key", AssistantMemoryStore.DEFAULT_STORAGE_KEY)
        Velocity.chatAssistant =
            Assistant(
                config,
                "chat",
                llmClient,
                AssistantMemoryStore(Velocity.redisManager, storageKey),
            )
    }

    override fun shutdown() {
        Velocity.chatAssistant = null
    }

    override fun reload() {
        Velocity.chatAssistant?.reload() ?: init()
    }
}
