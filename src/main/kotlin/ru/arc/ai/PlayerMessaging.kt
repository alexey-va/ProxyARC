package ru.arc.ai

import org.slf4j.LoggerFactory
import ru.arc.Utils
import ru.arc.config.ProxyConfigs
import ru.arc.velocity.Velocity

object PlayerMessaging {
    private val log = LoggerFactory.getLogger(PlayerMessaging::class.java)

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
            log.info(
                "PM to offline player {} (logged, not delivered): {}",
                name,
                text.take(120),
            )
            return mapOf(
                "status" to "offline",
                "player" to name,
                "logged" to true,
                "note" to "игрок не на прокси — текст записан в лог и тикет-диалог, доставка при simulate/offline ok",
            )
        }

        val config = ProxyConfigs.module("assistant.yml")
        val formatted = AssistantChatFormat.privateMessage(config, text)
        player.sendMessage(Utils.legacy(formatted))
        log.debug("PM sent to {}: {}", name, text.take(80))
        return mapOf("status" to "sent", "player" to name)
    }

    fun isPrivateMessageAccepted(result: Map<*, *>): Boolean =
        result["status"] == "sent" || result["status"] == "offline"

    fun sendGlobal(message: String): Map<String, Any?> {
        val text = message.trim()
        if (text.isEmpty()) return mapOf("status" to "error", "message" to "message required")

        val proxy = Velocity.proxyServer
            ?: return mapOf("status" to "error", "message" to "proxy unavailable")

        val config = ProxyConfigs.module("assistant.yml")
        val formatted = AssistantChatFormat.inGameMessage(config, text)
        val component = Utils.legacy(formatted)
        val players = proxy.allPlayers
        players.forEach { it.sendMessage(component) }
        return mapOf("status" to "sent", "recipients" to players.size)
    }
}
