package ru.arc.hooks

import org.slf4j.LoggerFactory
import ru.arc.core.PluginModule
import ru.arc.velocity.Velocity

private val log = LoggerFactory.getLogger(HooksModule::class.java)

// ==================== Priority 30: External Hooks ====================

object HooksModule : PluginModule {
    override val name = "Hooks"
    override val priority = 30

    override fun init() {
        val plugins = Velocity.requireProxyServer().pluginManager
        Velocity.luckpermsHook = null
        Velocity.liteBansHook = null
        if (plugins.getPlugin("luckperms").isPresent) {
            try { Velocity.luckpermsHook = LuckpermsHook() }
            catch (failure: Exception) { log.error("Error while initializing LuckpermsHook", failure) }
            catch (failure: LinkageError) { log.error("LuckPerms API is unavailable; permission integration remains disabled", failure) }
        }
        if (plugins.getPlugin("litebans").isPresent) {
            try { Velocity.liteBansHook = LiteBansHook() }
            catch (failure: Exception) { log.error("Error while initializing LiteBansHook", failure) }
            catch (failure: LinkageError) { log.error("LiteBans API is unavailable; moderation integration remains disabled", failure) }
        }
    }

    override fun shutdown() {
        Velocity.luckpermsHook = null
        Velocity.liteBansHook = null
    }

    override fun reload() {}
}
