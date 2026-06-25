package ru.arc.ai

import ru.arc.config.ProxyConfigs
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity

// ==================== Priority 85: AI Assistant ====================

object AssistantModule : PluginModule {
    override val name = "Assistant"
    override val priority = 85

    override fun init() {
        Velocity.chatAssistant =
            Assistant(ProxyConfigs.module("assistant.yml"), "chat")
    }

    override fun shutdown() {
        Velocity.chatAssistant = null
    }
}
