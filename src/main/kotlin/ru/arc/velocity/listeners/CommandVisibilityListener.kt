package ru.arc.velocity.listeners

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import java.util.Locale

internal interface CommandVisibilitySettings {
    val enabled: Boolean
    val bypassPermission: String
    val hideNamespacedRoots: Boolean
    val hiddenRoots: Set<String>
}

internal class CommandVisibilityConfig(
    private val config: Config = ProxyConfigs.module("command-visibility.yml"),
) : CommandVisibilitySettings {
    override val enabled: Boolean
        get() = config.bool("enabled", true)

    override val bypassPermission: String
        get() = config.string("bypass-permission", "arc.command.hide.bypass").trim()

    override val hideNamespacedRoots: Boolean
        get() = config.bool("hide-namespaced-roots", true)

    override val hiddenRoots: Set<String>
        get() =
            config
                .stringList("hidden-roots", DEFAULT_HIDDEN_ROOTS)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { it.lowercase(Locale.ROOT) }
                .toSet()

    private companion object {
        val DEFAULT_HIDDEN_ROOTS =
            listOf(
                "world",
                "worlds",
                "myworlds",
                "my_worlds",
                "mw",
                "mv",
                "litebans",
                "lbans",
                "br",
                "brush",
                "desel",
                "deselect",
                "sel",
                "toggleplace",
                "none",
                "pos",
                "tool",
                "auth",
                "botfilter",
                "limboauth",
                "limbofilter",
                "luckpermsvelocity",
                "lpv",
                "planbungee",
                "planproxy",
                "planvelocity",
                "creative",
                "god",
                "ungod",
                "heal",
                "tangledmaze",
                "maze",
                "gravesx",
            )
    }
}

/** Final network-side visibility guard after backend and proxy command trees are merged. */
internal class CommandVisibilityListener(
    private val config: CommandVisibilitySettings = CommandVisibilityConfig(),
) {
    @Subscribe(order = PostOrder.LAST, async = false)
    fun onAvailableCommands(event: PlayerAvailableCommandsEvent) {
        if (!config.enabled) return
        val bypass = config.bypassPermission
        if (bypass.isNotEmpty() && event.player.hasPermission(bypass)) return

        val hiddenRoots = config.hiddenRoots
        val roots = event.rootNode.children.iterator()
        while (roots.hasNext()) {
            val root = roots.next().name
            if (
                (config.hideNamespacedRoots && ':' in root) ||
                root.lowercase(Locale.ROOT) in hiddenRoots
            ) {
                roots.remove()
            }
        }
    }
}
