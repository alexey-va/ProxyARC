package ru.arc.telegram

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.velocity.Velocity
import ru.arc.Utils.mm
import ru.arc.Utils.plain
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import ru.arc.discord.DiscordBot
import java.util.concurrent.atomic.AtomicBoolean

open class TelegramBot(
    token: String = config.string("token", "none"),
    private val scheduler: TaskScheduler = Tasks.scheduler,
    requestExecutor: ((SendMessage) -> Unit)? = null,
) : TelegramLongPollingBot(token),
    AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val requestExecutor: (SendMessage) -> Unit = requestExecutor ?: { execute(it) }

    override fun onUpdateReceived(update: Update) {
        log.info("onUpdateReceived {}", update)
        val incoming = update.message ?: return
        val author = incoming.from ?: return
        if (author.isBot) return
        val text = incoming.text ?: return

        log.info(
            "Telegram message: {} in {} (TID: {})",
            text,
            incoming.chatId,
            incoming.messageThreadId,
        )

        val threadId = incoming.messageThreadId
        val sender = author.userName ?: author.firstName
        var message = text
        val chatId = incoming.chatId

        if (chatId != config.longValue("chat-id", 0)) return
        if (threadId != null && threadId == config.integer("topics.chat", 0)) {
            propagateChatMessage(sender, message)
        }
        if (threadId != null && threadId == config.integer("topics.general", 0)) {
            val format = config.string("discord-format", "**%sender%** » %message%")
            message = format
                .replace("%sender%", sender)
                .replace("%message%", message)
            Velocity.discordBot?.sendGeneralMessage(message)
        }
    }

    private fun propagateChatMessage(sender: String, message: String) {
        val discordFormat = config.string("discord-format", "**%sender%** » %message%")
        val discordMessage = discordFormat
            .replace("%sender%", sender)
            .replace("%message%", message)
        Velocity.discordBot?.sendChatMessage(discordMessage)

        val chatFormat = config.string("chat-format", "<blue>T <gray>%sender% <dark_gray>» <white>%message%")
        val chatMessage = chatFormat
            .replace("%sender%", sender)
            .replace("%message%", message)
        Velocity.plugin?.sendMessageToAll(mm(chatMessage))
    }

    fun sendChatMessage(message: String) {
        val sendMessage = SendMessage()
        sendMessage.chatId = config.longValue("chat-id", 0).toString()
        sendMessage.text = message
        sendMessage.messageThreadId = config.integer("topics.chat", 0)
        enqueue(sendMessage)
    }

    fun sendGeneralMessage(message: String) {
        val sendMessage = SendMessage()
        sendMessage.chatId = config.longValue("chat-id", 0).toString()
        sendMessage.text = message
        sendMessage.messageThreadId = config.integer("topics.general", -1)
        enqueue(sendMessage)
    }

    override fun getBotUsername(): String = "RusCrafting"

    fun sendJoinMessage(username: String, joinType: DiscordBot.JoinType, messageOverride: String?) {
        when (joinType) {
            DiscordBot.JoinType.JOIN -> {
                val message = messageOverride
                    ?: config.string("messages.${joinType.name.lowercase()}", "Игрок %player_name% присоединился к серверу")
                sendChatMessage(plain(message.replace("%player_name%", username)))
            }
            DiscordBot.JoinType.FIRST_TIME -> {
                val message = messageOverride
                    ?: config.string("messages.${joinType.name.lowercase()}", "Игрок %player_name% впервые присоединился к серверу")
                sendChatMessage(plain(message.replace("%player_name%", username)))
            }
            DiscordBot.JoinType.LEAVE -> {
                val message = messageOverride
                    ?: config.string("messages.${joinType.name.lowercase()}", "Игрок %player_name% покинул сервер")
                sendChatMessage(plain(message.replace("%player_name%", username)))
            }
        }
    }

    internal fun enqueue(message: SendMessage) {
        if (closed.get()) return
        scheduler.runAsync {
            if (closed.get()) return@runAsync
            try {
                requestExecutor(message)
            } catch (e: Exception) {
                log.error("Failed to send message to Telegram", e)
            }
        }
    }

    override fun close() {
        closed.set(true)
    }

    companion object {
        private val log = LoggerFactory.getLogger(TelegramBot::class.java)
        private val config: Config get() = ProxyConfigs.module("telegram.yml")
    }
}
