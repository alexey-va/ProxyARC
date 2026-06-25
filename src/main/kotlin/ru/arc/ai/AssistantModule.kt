package ru.arc.ai

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
        Velocity.chatAssistant =
            Assistant(ProxyConfigs.module("assistant.yml"), "chat", llmClient)
    }

    override fun shutdown() {
        Velocity.chatAssistant = null
    }
}
