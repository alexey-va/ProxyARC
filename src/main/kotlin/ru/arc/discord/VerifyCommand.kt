package ru.arc.discord

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
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
            val inviteUrl = bot.verificationInviteUrl()
            if (inviteUrl == null || !DiscordVerificationConfig.validInviteUrl(inviteUrl)) {
                if (player.isActive) {
                    player.sendMessage(
                        message("Сервис недоступен. Данные не изменены.", NamedTextColor.RED),
                    )
                }
                return@runAsync
            }
            val result =
                if (recovery) {
                    bot.issueRecoveryChallenge(player.uniqueId, player.username)
                } else {
                    bot.issueLinkChallenge(player.uniqueId, player.username)
                }
            if (!player.isActive) return@runAsync
            when (result) {
                is DiscordChallengeIssueResult.Issued ->
                    player.sendMessage(
                        challengeMessage(
                            code = result.code,
                            expiresInMinutes = retryMinutes(result.expiresAt),
                            recovery = recovery,
                            inviteUrl = inviteUrl,
                        ),
                    )
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

    companion object {
        private val ACCENT = TextColor.color(0x92BED8)
        private val BODY = TextColor.color(0xE6FFF3)
        private val STRUCTURE = TextColor.color(0x8C8C8C)
        private val MUTED = TextColor.color(0x969696)
        private val CODE = TextColor.color(0xFFACD5)

        internal fun challengeMessage(
            code: String,
            expiresInMinutes: Long,
            recovery: Boolean,
            inviteUrl: String,
        ): Component {
            require(DiscordVerificationConfig.validInviteUrl(inviteUrl)) { "invalid Discord invite URL" }
            val title = if (recovery) "Перенос привязки Discord" else "Привязка Discord-аккаунта"
            val copyEvent = ClickEvent.copyToClipboard(code)
            val codeComponent =
                Component.text(code, CODE, TextDecoration.BOLD)
                    .clickEvent(copyEvent)
                    .hoverEvent(Component.text("Нажмите, чтобы скопировать", MUTED))
            val codeRow =
                Component.text("Код для Discord: ", STRUCTURE)
                    .append(codeComponent)
                    .clickEvent(copyEvent)
                    .hoverEvent(Component.text("Скопировать код", MUTED))
            val inviteRow =
                Component.text("Открыть Discord RusCrafting", ACCENT, TextDecoration.BOLD)
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(inviteUrl))
                    .hoverEvent(Component.text("Перейти на сервер RusCrafting", MUTED))

            return Component.empty()
                .append(Component.newline())
                .append(indented(Component.text(title, ACCENT, TextDecoration.BOLD)))
                .append(Component.newline())
                .append(Component.newline())
                .append(indented(codeRow))
                .append(Component.newline())
                .append(indented(Component.text("Нажмите строку — код скопируется.", MUTED)))
                .append(Component.newline())
                .append(Component.newline())
                .append(indented(inviteRow))
                .append(Component.newline())
                .append(indented(Component.text("В Discord введите ", BODY).append(Component.text("/verify", ACCENT))))
                .append(Component.newline())
                .append(
                    indented(
                        Component.text("В поле ", BODY)
                            .append(Component.text("code", BODY))
                            .append(Component.text(" вставьте скопированный код.", BODY)),
                    ),
                )
                .append(Component.newline())
                .append(Component.newline())
                .append(
                    indented(
                        Component.text(
                            "Код действует $expiresInMinutes ${minutesWord(expiresInMinutes)}.",
                            MUTED,
                        ),
                    ),
                )
                .append(Component.newline())
        }

        private fun indented(content: Component): Component = Component.text("  ").append(content)

        private fun minutesWord(value: Long): String {
            val lastTwo = value % 100
            if (lastTwo in 11..14) return "минут"
            return when (value % 10) {
                1L -> "минуту"
                2L, 3L, 4L -> "минуты"
                else -> "минут"
            }
        }
    }
}
