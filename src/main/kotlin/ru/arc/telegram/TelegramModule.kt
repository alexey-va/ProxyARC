package ru.arc.telegram

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.generics.BotSession
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import ru.arc.config.ProxyConfigs
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity

private val log = LoggerFactory.getLogger(TelegramModule::class.java)

// ==================== Priority 75: Telegram ====================

object TelegramModule : PluginModule {
    override val name = "Telegram"
    override val priority = 75

    private val runtime =
        TelegramRuntime { bot ->
            TelegramBotsApi(DefaultBotSession::class.java).registerBot(bot)
        }

    override fun init() {
        shutdown()
        try {
            val telegramConfig = ProxyConfigs.module("telegram.yml")
            if (!telegramConfig.bool("enabled", false)) {
                log.info("TelegramBot is disabled in config")
                return
            }
            Velocity.telegramBot = runtime.start(TelegramBot())
            log.info("TelegramBot initialized")
        } catch (e: Exception) {
            runtime.close()
            Velocity.telegramBot = null
            log.error("Error while initializing TelegramBot", e)
        }
    }

    override fun shutdown() {
        runtime.close()
        Velocity.telegramBot = null
    }

    override fun reload() {
        init()
    }
}

internal class TelegramRuntime(
    private val register: (TelegramBot) -> BotSession,
) : AutoCloseable {
    private var bot: TelegramBot? = null
    private var session: BotSession? = null

    fun start(newBot: TelegramBot): TelegramBot {
        close()
        return try {
            val newSession = register(newBot)
            bot = newBot
            session = newSession
            newBot
        } catch (error: Exception) {
            newBot.close()
            throw error
        }
    }

    override fun close() {
        bot?.close()
        bot = null
        session?.let { activeSession ->
            runCatching {
                if (activeSession.isRunning) {
                    activeSession.stop()
                }
            }.onFailure { error ->
                log.warn("Failed to stop Telegram bot session", error)
            }
        }
        session = null
    }
}
