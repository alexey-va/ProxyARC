package ru.arc.rtp

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import ru.arc.Utils
import java.util.concurrent.CompletableFuture

class RtpCommand(
    private val manager: RtpRequestManager,
    private val config: ProxyRtpConfig,
) : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val player =
            invocation.source() as? Player
                ?: run {
                    invocation.source().sendMessage(Utils.mm("<red>Эта команда доступна только игроку."))
                    return
                }
        val args = invocation.arguments()
        if (args.size > 1) {
            player.sendMessage(Utils.mm("<yellow>Использование: <white>/rtp [${config.allowedWorlds.joinToString("|")}]"))
            return
        }
        manager.request(player, args.firstOrNull())
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val args = invocation.arguments()
        if (args.size > 1) return emptyList()
        val prefix = args.firstOrNull().orEmpty()
        return config.allowedWorlds.filter { it.startsWith(prefix, ignoreCase = true) }
    }

    override fun suggestAsync(invocation: SimpleCommand.Invocation): CompletableFuture<List<String>> =
        CompletableFuture.completedFuture(suggest(invocation))

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean = true
}
