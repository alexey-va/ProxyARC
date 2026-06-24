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
        try {
            Velocity.luckpermsHook = LuckpermsHook()
        } catch (e: Exception) {
            log.error("Error while initializing LuckpermsHook", e)
        }

        try {
            Velocity.liteBansHook = LiteBansHook()
        } catch (e: Exception) {
            log.error("Error while initializing LiteBansHook", e)
        }
    }

    override fun shutdown() {
        Velocity.luckpermsHook = null
        Velocity.liteBansHook = null
    }
}
