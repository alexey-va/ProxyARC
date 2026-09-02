package ru.arc.velocity.listeners

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.RootCommandNode
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent
import com.velocitypowered.api.proxy.Player
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files

class CommandVisibilityListenerTest :
    FreeSpec({
        "removes late infrastructure roots and every namespaced duplicate" {
            val player = playerWithPermissions()
            val root = commandRoot("world", "litebans", "litebans:litebans", "home", "pay", "pwarp")

            listener().onAvailableCommands(PlayerAvailableCommandsEvent(player, root))

            root.children.map { it.name } shouldContainExactlyInAnyOrder listOf("home", "pay", "pwarp")
        }

        "keeps the full command tree for an explicit bypass" {
            val player = playerWithPermissions("arc.command.hide.bypass")
            val root = commandRoot("world", "litebans:litebans", "home")

            listener().onAvailableCommands(PlayerAvailableCommandsEvent(player, root))

            root.children.map { it.name } shouldContainExactlyInAnyOrder
                listOf("world", "litebans:litebans", "home")
        }

        "bundled policy hides owner-selected technical roots and keeps gameplay roots" {
            val directory = Files.createTempDirectory("proxyarc-command-visibility-")
            Config.copyDefaultConfig(
                ConfigManager.bundledModuleResource("command-visibility.yml"),
                directory,
                replace = false,
            )
            val settings = CommandVisibilityConfig(ConfigManager.ofModule(directory, "command-visibility.yml"))
            val hidden = settings.hiddenRoots

            hidden.containsAll(
                setOf(
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
                ),
            ) shouldBe true
            hidden.intersect(
                setOf(
                    "arc",
                    "builder",
                    "cmi",
                    "hack",
                    "infinityexpansion",
                    "geneticchickengineering",
                    "foxymachines",
                    "emctech",
                ),
            ) shouldBe emptySet()
        }
    })

private fun listener(): CommandVisibilityListener {
    val settings =
        object : CommandVisibilitySettings {
            override val enabled = true
            override val bypassPermission = "arc.command.hide.bypass"
            override val hideNamespacedRoots = true
            override val hiddenRoots =
                setOf("world", "worlds", "myworlds", "my_worlds", "mw", "mv", "litebans", "lbans")
        }
    return CommandVisibilityListener(settings)
}

private fun playerWithPermissions(vararg permissions: String): Player {
    val player = mockk<Player>()
    val granted = permissions.toSet()
    every { player.hasPermission(any<String>()) } answers { firstArg<String>() in granted }
    return player
}

private fun commandRoot(vararg labels: String): RootCommandNode<Any> =
    RootCommandNode<Any>().also { root ->
        labels.forEach { label -> root.addChild(LiteralArgumentBuilder.literal<Any>(label).build()) }
    }
