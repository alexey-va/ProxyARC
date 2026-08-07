package ru.arc.velocity.listeners

import com.velocitypowered.api.event.player.PlayerChatEvent
import ru.arc.chat.ChatMode

internal class ChatModeEventProcessor(
    private val modeProvider: (java.util.UUID) -> ChatMode,
) {
    fun apply(event: PlayerChatEvent): Outcome {
        val before = effectiveMessage(event)
        val mode = modeProvider(event.player.uniqueId)
        if (!event.result.isAllowed || mode != ChatMode.GLOBAL || before.startsWith("!")) {
            return Outcome(mode, before, prefixAdded = false)
        }

        val replacement = "!$before"
        event.setResult(PlayerChatEvent.ChatResult.message(replacement))
        return Outcome(mode, replacement, prefixAdded = true)
    }

    data class Outcome(
        val mode: ChatMode,
        val effectiveMessage: String,
        val prefixAdded: Boolean,
    )

    companion object {
        fun effectiveMessage(event: PlayerChatEvent): String =
            event.result.message.orElse(event.message)
    }
}
