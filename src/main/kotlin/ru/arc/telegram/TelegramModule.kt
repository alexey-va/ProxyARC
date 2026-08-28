package ru.arc.telegram

import org.slf4j.LoggerFactory
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.generics.BotSession
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import ru.arc.core.PluginModule
import ru.arc.config.ProxyConfigs
import ru.arc.velocity.Velocity
import java.util.concurrent.TimeUnit

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
            val telegramConfig = TelegramConfig.load()
            if (!telegramConfig.enabled) {
                log.info("TelegramBot is disabled in config")
                return
            }
            telegramConfig.validate()
            val proxySettings = TelegramProxySettings.from(ProxyConfigs.module("llm-network.yml"))
            val identity =
                if (telegramConfig.identityEnabled) {
                    runCatching {
                        TelegramIdentityService(
                            TelegramIdentityStore(Velocity.requireDataFolder()),
                            telegramConfig,
                        )
                    }.onFailure { error ->
                        log.error("Telegram identity service is unavailable", error)
                    }.getOrNull()
                } else {
                    null
                }
            val bot =
                runtime.start(
                    TelegramBot(
                        telegramConfig,
                        identityService = identity,
                        botOptions = proxySettings.botOptions(),
                    ),
                )
            bot.probeConnectivity().get(CONNECTIVITY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            Velocity.telegramBot = bot
            log.info("TelegramBot initialized proxy={}", proxySettings.enabled)
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

    private const val CONNECTIVITY_TIMEOUT_SECONDS = 15L
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
