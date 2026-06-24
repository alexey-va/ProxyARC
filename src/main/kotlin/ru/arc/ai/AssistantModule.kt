package ru.arc.ai

import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity

// ==================== Priority 85: AI Assistant ====================

object AssistantModule : PluginModule {
    override val name = "Assistant"
    override val priority = 85

    override fun init() {
        Velocity.chatAssistant =
            Assistant(ConfigManager.of(Velocity.dataFolder!!, "assistant.yml"), "chat")
    }

    override fun shutdown() {
        Velocity.chatAssistant = null
    }
}
