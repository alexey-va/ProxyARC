package ru.arc.discord

import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

internal class DiscordListener(
    private val chat: DiscordChatService,
) : ListenerAdapter() {
    override fun onMessageReceived(event: MessageReceivedEvent) {
        chat.onMessage(event)
    }

    override fun onMessageUpdate(event: MessageUpdateEvent) {
        chat.onMessageUpdate(event)
    }

    override fun onMessageDelete(event: MessageDeleteEvent) {
        chat.onMessageDelete(event)
    }
}
