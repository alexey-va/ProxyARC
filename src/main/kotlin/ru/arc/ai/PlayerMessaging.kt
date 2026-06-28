package ru.arc.ai

import ru.arc.Utils
import ru.arc.config.ProxyConfigs
import ru.arc.velocity.Velocity

object PlayerMessaging {
    fun sendPrivate(playerName: String, message: String): Map<String, Any?> {
        val name = playerName.trim()
        val text = message.trim()
        if (name.isEmpty()) return mapOf("status" to "error", "message" to "playerName required")
        if (text.isEmpty()) return mapOf("status" to "error", "message" to "message required")

        val proxy = Velocity.proxyServer
        if (proxy == null) {
            return mapOf("status" to "error", "message" to "proxy unavailable")
        }
        val player = proxy.getPlayer(name).orElse(null)
        if (player == null) {
            return mapOf("status" to "offline", "player" to name)
        }

        val config = ProxyConfigs.module("assistant.yml")
        val formatted = AssistantChatFormat.privateMessage(config, text)
        player.sendMessage(Utils.legacy(formatted))
        return mapOf("status" to "sent", "player" to name)
    }
}
