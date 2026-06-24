package ru.arc.telegram

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity

private val log = LoggerFactory.getLogger(TelegramModule::class.java)

// ==================== Priority 75: Telegram ====================

object TelegramModule : PluginModule {
    override val name = "Telegram"
    override val priority = 75

    override fun init() {
        try {
            val telegramConfig = ConfigManager.of(Velocity.dataFolder!!, "telegram.yml")
            if (!telegramConfig.bool("enabled", false)) {
                log.info("TelegramBot is disabled in config")
                return
            }
            val telegramBotsApi = TelegramBotsApi(DefaultBotSession::class.java)
            Velocity.telegramBot = TelegramBot()
            telegramBotsApi.registerBot(Velocity.telegramBot)
            log.info("TelegramBot initialized")
        } catch (e: Exception) {
            log.error("Error while initializing TelegramBot", e)
        }
    }

    override fun shutdown() {
        Velocity.telegramBot = null
    }

    override fun reload() {}
}
