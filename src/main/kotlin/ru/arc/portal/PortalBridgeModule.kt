package ru.arc.portal

import org.slf4j.LoggerFactory
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity

object PortalBridgeModule : PluginModule {
    override val name: String = "PortalBridge"
    override val priority: Int = 86

    private var tasks: LifecycleTaskScope? = null

    override fun init() {
        shutdown()
        val config = PortalBridgeConfig.load()
        if (!config.enabled) {
            log.info("Portal bridge is disabled")
            return
        }
        try {
            val service = PortalBridgeService(config, Velocity.requireLogger())
            val scope = LifecycleTaskScope()
            Velocity.portalBridge = service
            tasks = scope
            scope.runTimerAsync(0, config.presenceIntervalTicks) {
                val players =
                    Velocity.requireProxyServer().allPlayers.map { player ->
                        PortalPresencePlayer(
                            minecraftUuid = player.uniqueId,
                            minecraftName = player.username,
                            server = player.currentServer.map { it.serverInfo.name }.orElse(null),
                        )
                    }
                service.publishPresence(System.currentTimeMillis(), players)
            }
            scope.runTimerAsync(0, config.identityIntervalTicks) {
                val capturedAt = System.currentTimeMillis()
                Velocity.discordBot?.identitySnapshot()?.let { links ->
                    service.publishIdentitySnapshot(
                        PortalIdentityProvider.DISCORD,
                        capturedAt,
                        links.map { link ->
                            PortalExternalIdentity(
                                providerUserId = link.discordUserId,
                                minecraftUuid = link.playerUuid,
                                minecraftName = link.playerName,
                                linkedAt = link.linkedAt,
                                updatedAt = link.updatedAt,
                            )
                        },
                    )
                }
                Velocity.telegramBot?.identitySnapshot()?.let { links ->
                    service.publishIdentitySnapshot(
                        PortalIdentityProvider.TELEGRAM,
                        capturedAt,
                        links.map { link ->
                            PortalExternalIdentity(
                                providerUserId = link.telegramUserId.toString(),
                                minecraftUuid = link.playerUuid,
                                minecraftName = link.playerName,
                                linkedAt = link.linkedAt,
                                updatedAt = link.updatedAt,
                            )
                        },
                    )
                }
            }
            log.info(
                "Portal bridge ready presenceTicks={} identityTicks={}",
                config.presenceIntervalTicks,
                config.identityIntervalTicks,
            )
        } catch (error: Exception) {
            shutdown()
            log.error("Portal bridge failed to initialize", error)
        }
    }

    override fun reload() = init()

    override fun shutdown() {
        tasks?.close()
        tasks = null
        Velocity.portalBridge?.close()
        Velocity.portalBridge = null
    }

    private val log = LoggerFactory.getLogger(PortalBridgeModule::class.java)
}
