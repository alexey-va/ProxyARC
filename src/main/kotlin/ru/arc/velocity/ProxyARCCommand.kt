package ru.arc.velocity

import com.velocitypowered.api.command.SimpleCommand
import ru.arc.Utils
import ru.arc.ai.Assistant
import ru.arc.config.ConfigManager
import ru.arc.core.ModuleRegistry
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
            commandSource.sendMessage(Utils.mm("This is ProxyARC plugin for Velocity!"))
        } else {
            when {
                args[0].equals("reload", ignoreCase = true) -> {
                    Velocity.firstJoinData?.save()
                    ModuleRegistry.reloadAll()
                    Assistant.assistants.forEach { it.reload() }
                    commandSource.sendMessage(Utils.mm("Перезагрузка успешна!"))
                }
                args[0].equals("cleardiscord", ignoreCase = true) -> {
                    if (args.size != 3) {
                        commandSource.sendMessage(Utils.mm("Usage: /arc cleardiscord <channelId> start/stop"))
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
                            commandSource.sendMessage(Utils.mm("Usage: /arc cleardiscord <channelId> start/stop"))
                    }
                }
                else -> commandSource.sendMessage(Utils.mm("Unknown command!"))
            }
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> =
        listOf("reload", "resetai", "cleardiscord")

    override fun suggestAsync(invocation: SimpleCommand.Invocation): CompletableFuture<List<String>> =
        CompletableFuture.completedFuture(listOf("reload", "resetai", "cleardiscord"))

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean =
        invocation.source().hasPermission("arc.admin")
}
