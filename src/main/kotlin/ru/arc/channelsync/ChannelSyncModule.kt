package ru.arc.channelsync

import org.slf4j.LoggerFactory
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity

object ChannelSyncModule : PluginModule {
    override val name: String = "ChannelSync"
    override val priority: Int = 78

    override fun init() {
        shutdown()
        val config = ChannelSyncConfig.load()
        if (!config.enabled) {
            log.info("Generic Discord/Telegram channel sync is disabled")
            return
        }
        try {
            val mappings = config.validatedMappings()
            if (mappings.isEmpty()) {
                log.info("Generic Discord/Telegram channel sync has no mappings")
                return
            }
            Velocity.channelSync =
                ChannelSyncService(
                    mappings = mappings,
                    links = ChannelSyncLinkStore(Velocity.requireDataFolder()),
                    discordProvider = { Velocity.discordBot },
                    telegramProvider = { Velocity.telegramBot },
                    identityResolver = identityResolver(),
                )
            log.info("Generic Discord/Telegram channel sync ready mappings={}", mappings.size)
        } catch (error: Exception) {
            Velocity.channelSync = null
            log.error("Generic Discord/Telegram channel sync failed to initialize", error)
        }
    }

    override fun reload() = init()

    override fun shutdown() {
        Velocity.channelSync?.close()
        Velocity.channelSync = null
    }

    internal fun textCodec(): ChannelSyncTextCodec =
        ChannelSyncTextCodec(
            identities = identityResolver(),
            telegramChannelMentionByDiscordId = { channelId ->
                Velocity.channelSync?.telegramUsernameForDiscordChannel(channelId)
            },
            discordChannelMentionByTelegramUsername = { username ->
                Velocity.channelSync?.discordChannelForTelegramUsername(username)
            },
        )

    private fun identityResolver(): ChannelSyncIdentityResolver =
        ChannelSyncIdentityResolver(
            telegramByDiscordUserId = { discordUserId ->
                Velocity.discordBot?.findIdentityByDiscordUser(discordUserId)?.let { discordLink ->
                    Velocity.telegramBot?.findIdentityByPlayer(discordLink.playerUuid)?.let { telegramLink ->
                        TelegramMentionTarget(telegramLink.telegramUserId, discordLink.playerName)
                    }
                }
            },
            discordByTelegramUserId = { telegramUserId ->
                Velocity.telegramBot?.findIdentityByTelegramUser(telegramUserId)?.let { telegramLink ->
                    Velocity.discordBot?.findIdentityByPlayer(telegramLink.playerUuid)?.let { discordLink ->
                        DiscordMentionTarget(discordLink.discordUserId, telegramLink.playerName)
                    }
                }
            },
            discordByTelegramUsername = { telegramUsername ->
                Velocity.telegramBot?.findIdentityByTelegramUsername(telegramUsername)?.let { telegramLink ->
                    Velocity.discordBot?.findIdentityByPlayer(telegramLink.playerUuid)?.let { discordLink ->
                        DiscordMentionTarget(discordLink.discordUserId, telegramLink.playerName)
                    }
                }
            },
        )

    private val log = LoggerFactory.getLogger(ChannelSyncModule::class.java)
}
