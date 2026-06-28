package ru.arc.velocity

import com.velocitypowered.api.command.SimpleCommand
import ru.arc.Utils
import ru.arc.core.modules.ProxyArcReload
import java.util.concurrent.CompletableFuture

class ProxyARCCommand : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val commandSource = invocation.source()
        val args = invocation.arguments()
        if (!commandSource.hasPermission("arc.admin")) {
            commandSource.sendMessage(Utils.mm("У вас нет разрешения на использование этой команды"))
            return
        }
        if (args.isEmpty()) {
            commandSource.sendMessage(
                Utils.mm(
                    "<green>ProxyARC\n" +
                        "<gray>/proxyarc reload — конфиги и промпт\n" +
                        "<gray>/proxyarc cleardiscord <channelId> start|stop",
                ),
            )
            return
        }
        when {
            args[0].equals("reload", ignoreCase = true) -> {
                ProxyArcReload.configsAndAssistant()
                commandSource.sendMessage(
                    Utils.mm("<green>Конфиги и промпт скорена перезагружены. Discord/Redis — только restart velocity."),
                )
            }
            args[0].equals("cleardiscord", ignoreCase = true) -> {
                if (args.size != 3) {
                    commandSource.sendMessage(Utils.mm("Usage: /proxyarc cleardiscord <channelId> start/stop"))
                    return
                }
                val channelId = args[1]
                val action = args[2]
                val discordBot = Velocity.discordBot
                when {
                    action.equals("start", ignoreCase = true) ->
                        discordBot?.clearChat(channelId)
                    action.equals("stop", ignoreCase = true) ->
                        discordBot?.stopClearTask(channelId)
                    else ->
                        commandSource.sendMessage(Utils.mm("Usage: /proxyarc cleardiscord <channelId> start/stop"))
                }
            }
            else -> commandSource.sendMessage(Utils.mm("Unknown command!"))
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        if (!invocation.source().hasPermission("arc.admin")) {
            return emptyList()
        }
        val args = invocation.arguments()
        // Velocity: tab on first arg often passes args.size 0 until a char is typed
        if (args.isEmpty() || (args.size == 1 && args[0].isEmpty())) {
            return ROOT_SUGGESTIONS
        }
        if (args.size == 1) {
            return ROOT_SUGGESTIONS.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args[0].equals("cleardiscord", ignoreCase = true) && args.size == 3) {
            return listOf("start", "stop").filter { it.startsWith(args[2], ignoreCase = true) }
        }
        return emptyList()
    }

    override fun suggestAsync(invocation: SimpleCommand.Invocation): CompletableFuture<List<String>> =
        CompletableFuture.completedFuture(suggest(invocation))

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean =
        invocation.source().hasPermission("arc.admin")

    companion object {
        private val ROOT_SUGGESTIONS = listOf("reload", "cleardiscord")
    }
}
