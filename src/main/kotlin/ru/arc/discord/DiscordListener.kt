package ru.arc.discord

import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

internal class DiscordListener(
    private val chat: DiscordChatService,
) : ListenerAdapter() {
    override fun onMessageReceived(event: MessageReceivedEvent) {
        chat.onMessage(event)
    }
}
