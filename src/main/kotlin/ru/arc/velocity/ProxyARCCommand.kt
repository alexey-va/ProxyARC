package ru.arc.velocity

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import ru.arc.Utils
import ru.arc.core.modules.ProxyArcReload
import java.time.Duration
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
                        "<gray>/proxyarc restart [-delay 30s] — countdown и graceful restart\n" +
                        "<gray>/proxyarc restart cancel — отменить countdown\n" +
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
            args[0].equals("restart", ignoreCase = true) -> handleRestart(commandSource, args)
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

    private fun handleRestart(
        source: CommandSource,
        args: Array<String>,
    ) {
        val service = Velocity.proxyRestartService
        if (service == null) {
            source.sendMessage(Utils.mm("<red>PROXYARC_RESTART unavailable"))
            return
        }

        if (args.getOrNull(1).equals("cancel", ignoreCase = true)) {
            if (service.cancel("console")) {
                source.sendMessage(Utils.mm("<yellow>PROXYARC_RESTART cancelled"))
            } else {
                source.sendMessage(Utils.mm("<gray>PROXYARC_RESTART nothing-pending"))
            }
            return
        }

        val delay = parseRestartDelay(args)
        if (delay == null) {
            source.sendMessage(Utils.mm("<red>Usage: /proxyarc restart [-delay 30s] | cancel"))
            return
        }

        when (val result = service.schedule(delay, "console")) {
            is ProxyRestartScheduleResult.Scheduled -> {
                val seconds = result.plan.delay.toSeconds()
                source.sendMessage(
                    Utils.mm(
                        "<green>PROXYARC_RESTART scheduled delay=${seconds}s players=${result.plan.playersAtSchedule}",
                    ),
                )
            }
            ProxyRestartScheduleResult.AlreadyPending ->
                source.sendMessage(Utils.mm("<red>PROXYARC_RESTART already-pending"))
        }
    }

    private fun parseRestartDelay(args: Array<String>): Duration? {
        if (args.size == 1) return Duration.ofSeconds(DEFAULT_RESTART_DELAY_SECONDS)
        val tokens = args.drop(1)
        val raw =
            when {
                tokens.size == 2 && tokens[0].equals("-delay", ignoreCase = true) -> tokens[1]
                tokens.size == 1 && tokens[0].startsWith("-delay:", ignoreCase = true) ->
                    tokens[0].substringAfter(':')
                else -> return null
            }
        return ProxyRestartDuration.parse(raw)
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
        if (args[0].equals("restart", ignoreCase = true)) {
            return when (args.size) {
                2 -> listOf("cancel", "-delay", "-delay:30s").filter { it.startsWith(args[1], ignoreCase = true) }
                3 -> if (args[1].equals("-delay", ignoreCase = true)) RESTART_DELAYS.filter {
                    it.startsWith(args[2], ignoreCase = true)
                } else emptyList()
                else -> emptyList()
            }
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
        private const val DEFAULT_RESTART_DELAY_SECONDS = 30L
        private val ROOT_SUGGESTIONS = listOf("reload", "restart", "cleardiscord")
        private val RESTART_DELAYS = listOf("10s", "30s", "1m", "3m", "5m")
    }
}
