package ru.ruscrafting.votes.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** Adds proxy-wide player suggestions while forwarding execution to ArcVotes on Paper. */
class NetworkVoteCommand(
    private val proxy: ProxyServer,
) {
    val command: BrigadierCommand = BrigadierCommand(
        BrigadierCommand.literalArgumentBuilder("vote")
            .executes { BrigadierCommand.FORWARD }
            .then(
                BrigadierCommand.requiredArgumentBuilder<String>(
                    "arguments",
                    StringArgumentType.greedyString(),
                )
                    .suggests { context, builder -> suggest(context.source, builder) }
                    .executes { BrigadierCommand.FORWARD },
            ),
    )

    private fun suggest(
        source: CommandSource,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val remaining = builder.remaining
        val lastSpace = remaining.lastIndexOf(' ')
        val prefix = remaining.substring(lastSpace + 1)
        val candidates = when {
            lastSpace < 0 -> buildList {
                if (source.hasPermission(STATUS_PERMISSION)) add("status")
                if (source.hasPermission(INSPECT_PERMISSION)) addAll(INSPECTION_COMMANDS)
            }

            source.hasPermission(INSPECT_PERMISSION) &&
                remaining.substring(0, lastSpace).trim().lowercase(Locale.ROOT) in INSPECTION_COMMANDS ->
                proxy.allPlayers
                    .map(Player::getUsername)
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)

            else -> emptyList()
        }
        val replacement = builder.createOffset(builder.start + lastSpace + 1)
        candidates
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .forEach(replacement::suggest)
        return replacement.buildFuture()
    }

    private companion object {
        const val STATUS_PERMISSION = "arcvotes.admin.status"
        const val INSPECT_PERMISSION = "arcvotes.admin.inspect"
        val INSPECTION_COMMANDS = listOf("check", "history")
    }
}
