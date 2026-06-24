package ru.arc.velocity.listeners

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.LoggerFactory
import ru.arc.CommonCore
import ru.arc.Utils
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.velocity.Velocity
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ChatListener(
    private val commonCore: CommonCore,
    private val proxyServer: ProxyServer,
    private val jippityConfig: Config,
) {
    private val mainConfig: Config = ConfigManager.of(Velocity.dataFolder!!, "config.yml")
    private val assistantConfig: Config = ConfigManager.of(Velocity.dataFolder!!, "assistant.yml")

    private val warnings = ConcurrentHashMap<UUID, ModerationStatus>()

    private data class ModerationStatus(
        val warns: Int,
        val lastWarn: Long,
    )

    @Subscribe(async = true)
    fun onChatMessage(event: PlayerChatEvent) {
        aiProcess(event)
        chatProcess(event)
    }

    private fun chatProcess(event: PlayerChatEvent) {
        if (!event.result.isAllowed) return
        if (!event.message.startsWith("!")) return
        val username = event.player.username
        val ip = event.player.remoteAddress.address.hostAddress
        val uuid = event.player.uniqueId

        if (commonCore.liteBansHook != null && commonCore.liteBansHook!!.isMuted(uuid, ip)) return

        val message = event.message.substring(1)
        val player = event.player
        val firstJoinTime = commonCore.firstJoinData!!.getFirstJoinTime(player.username)
        val minPlayerTime = mainConfig.integer("discord.min-play-time-sec", 600) * 1000L
        if (firstJoinTime == null || firstJoinTime + minPlayerTime > System.currentTimeMillis()) return
        CompletableFuture.runAsync {
            val pattern = mainConfig.string("discord.chat-pattern", "**%player_name%** » %message%")
            var chatMessage = pattern.replace("%player_name%", username).replace("%message%", message)
            commonCore.discordBot!!.sendChatMessage(chatMessage)

            val telegramPattern = mainConfig.string("telegram.chat-pattern", "\\*\\*%player_name%\\*\\* » %message%")
            chatMessage = telegramPattern.replace("%player_name%", username).replace("%message%", message)
            commonCore.telegramBot!!.sendChatMessage(chatMessage)
        }
    }

    private fun aiProcess(event: PlayerChatEvent) {
        if (!event.result.isAllowed) return
        if (!event.message.startsWith("!")) return
        val message = event.message.substring(1)
        val playerName = event.player.username

        commonCore.chatAssistant!!.addChatMessage(message, playerName)
        commonCore.chatAssistant!!.tryEnqueue().thenAccept { response ->
            try {
                val prefix = assistantConfig.string("chat-prefix", "<gold>Бот <gray>» </gray><white>")
                log.info("Chat assistant response: {}", response)
                if (response.isEmpty) {
                    log.error("Empty response from chat assistant")
                    return@thenAccept
                }
                val reply = response.get()
                val chatMessages = reply.replace("\n\n", "\n").split("\n")
                var delay = 0
                for (chatMessage in chatMessages) {
                    if (chatMessage.equals("пропускаю", ignoreCase = true)) continue
                    val component = Utils.mm(prefix + chatMessage.trim())
                    proxyServer.scheduler
                        .buildTask(
                            Velocity.plugin!!,
                            Runnable {
                                proxyServer.allPlayers
                                    .onEach { log.info("Sending AI message to player {}", it.username) }
                                    .forEach { it.sendMessage(component) }
                            },
                        )
                        .delay(delay.toLong(), TimeUnit.SECONDS)
                        .schedule()
                    delay += 4
                }
            } catch (e: Exception) {
                log.error("Error while processing chat assistant response", e)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChatListener::class.java)
    }
}
