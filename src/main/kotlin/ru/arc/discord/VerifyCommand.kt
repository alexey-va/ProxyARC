package ru.arc.discord

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import ru.arc.core.Tasks
import ru.arc.velocity.Velocity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class VerifyCommand : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val player = invocation.source() as? Player
        if (player == null) {
            invocation.source().sendMessage(message("Только для игроков.", NamedTextColor.RED))
            return
        }
        val bot = Velocity.discordBot
        if (bot == null || !bot.isVerificationEnabled()) {
            player.sendMessage(message("Проверка Discord временно недоступна.", NamedTextColor.RED))
            return
        }
        val backend = player.currentServer.map { it.serverInfo.name }.orElse(null)
        if (!player.isActive || backend == null || !bot.isVerificationBackendAllowed(backend)) {
            player.sendMessage(
                message("Сначала войдите на игровой сервер.", NamedTextColor.YELLOW),
            )
            return
        }
        when (invocation.arguments().map(String::lowercase)) {
            emptyList<String>() -> issue(player, recovery = false)
            listOf("status") -> showStatus(player)
            listOf("recover") -> issue(player, recovery = true)
            listOf("unlink", "confirm") -> unlink(player)
            else ->
                player.sendMessage(
                    message("/verify [status|recover|unlink confirm]", NamedTextColor.YELLOW),
                )
        }
    }

    private fun issue(
        player: Player,
        recovery: Boolean,
    ) {
        Tasks.scheduler.runAsync {
            val bot = Velocity.discordBot ?: return@runAsync
            val result =
                if (recovery) {
                    bot.issueRecoveryChallenge(player.uniqueId, player.username)
                } else {
                    bot.issueLinkChallenge(player.uniqueId, player.username)
                }
            if (!player.isActive) return@runAsync
            when (result) {
                is DiscordChallengeIssueResult.Issued -> {
                    player.sendMessage(
                        prefix().append(Component.text("Код: ", NamedTextColor.GRAY))
                            .append(Component.text(result.code, NamedTextColor.YELLOW)),
                    )
                    player.sendMessage(
                        message(
                            "В Discord: /verify → code. Срок — ${retryMinutes(result.expiresAt)} мин.",
                            NamedTextColor.GRAY,
                        ),
                    )
                }
                is DiscordChallengeIssueResult.AlreadyLinked ->
                    player.sendMessage(message("Уже связан. Перенос: /verify recover."))
                DiscordChallengeIssueResult.NotLinked ->
                    player.sendMessage(message("Аккаунт не связан. Используйте /verify."))
                is DiscordChallengeIssueResult.RateLimited ->
                    player.sendMessage(
                        message("Новый код через ${retryMinutes(result.retryAt)} мин."),
                    )
                DiscordChallengeIssueResult.Unavailable ->
                    player.sendMessage(message("Сервис недоступен. Данные сохранены.", NamedTextColor.RED))
            }
        }
    }

    private fun showStatus(player: Player) {
        Tasks.scheduler.runAsync {
            val link = Velocity.discordBot?.findIdentityByPlayer(player.uniqueId)
            if (!player.isActive) return@runAsync
            player.sendMessage(
                if (link == null) {
                    message("Discord-аккаунт не связан.")
                } else {
                    message("Связан: ${link.playerName}.", NamedTextColor.GREEN)
                },
            )
        }
    }

    private fun unlink(player: Player) {
        Tasks.scheduler.runAsync {
            val bot = Velocity.discordBot ?: return@runAsync
            bot.unlinkIdentityByMinecraft(player.uniqueId).whenComplete { result, error ->
                if (!player.isActive) return@whenComplete
                val response =
                    when {
                        error != null -> message("Отвязать не удалось. Повторите позже.", NamedTextColor.RED)
                        result is DiscordVerificationWorkflowResult.Unlinked ->
                            message("Аккаунт отвязан, роли сняты.", NamedTextColor.GREEN)
                        result is DiscordVerificationWorkflowResult.NotLinked -> message("Discord-аккаунт не связан.")
                        result is DiscordVerificationWorkflowResult.RoleFailure ->
                            message("Бот не может снять роли. Отвязка отменена.", NamedTextColor.RED)
                        else -> message("Отвязать не удалось. Данные сохранены.", NamedTextColor.RED)
                    }
                player.sendMessage(response)
            }
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val args = invocation.arguments()
        return when (args.size) {
            0, 1 -> listOf("status", "recover", "unlink").filter { it.startsWith(args.firstOrNull().orEmpty(), true) }
            2 -> if (args[0].equals("unlink", true) && "confirm".startsWith(args[1], true)) listOf("confirm") else emptyList()
            else -> emptyList()
        }
    }

    override fun suggestAsync(invocation: SimpleCommand.Invocation): CompletableFuture<List<String>> =
        CompletableFuture.completedFuture(suggest(invocation))

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean = true

    private fun prefix(): Component =
        Component.text("Discord", NamedTextColor.BLUE).append(Component.text(" • ", NamedTextColor.DARK_GRAY))

    private fun message(
        text: String,
        color: NamedTextColor = NamedTextColor.GRAY,
    ): Component = prefix().append(Component.text(text, color))

    private fun retryMinutes(retryAt: Long): Long =
        TimeUnit.MILLISECONDS.toMinutes((retryAt - System.currentTimeMillis()).coerceAtLeast(0) + 59_999)
            .coerceAtLeast(1)
}
