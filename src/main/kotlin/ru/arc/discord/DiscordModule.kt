package ru.arc.discord

import org.slf4j.LoggerFactory
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity

private val log = LoggerFactory.getLogger(DiscordModule::class.java)

// ==================== Priority 70: Discord ====================

object DiscordModule : PluginModule {
    override val name = "Discord"
    override val priority = 70

    override fun init() {
        try {
            Velocity.discordBot = DiscordBot()
        } catch (e: Exception) {
            log.error("Error initializing discord bot", e)
        }
    }

    override fun shutdown() {
        Velocity.discordBot = null
    }

    /** JDA lifecycle is not hot-reloadable; avoid spawning a second bot instance. */
    override fun reload() {}
}
