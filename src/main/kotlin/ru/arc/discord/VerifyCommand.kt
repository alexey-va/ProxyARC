package ru.arc.discord

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import ru.arc.core.Tasks
import ru.arc.telegram.TelegramChallengeIssueResult
import ru.arc.telegram.TelegramUnlinkResult
import ru.arc.velocity.Velocity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class VerifyCommand : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val messages = messages()
        val player = invocation.source() as? Player
        if (player == null) {
            invocation.source().sendMessage(messages.minecraft("only-player"))
            return
        }
        val arguments = invocation.arguments().map(String::lowercase)
        if (arguments.firstOrNull() == "telegram") {
            executeTelegram(player, arguments.drop(1))
            return
        }
        val bot = Velocity.discordBot
        if (bot == null || !bot.isVerificationEnabled()) {
            player.sendMessage(messages.minecraft("unavailable"))
            return
        }
        val backend = player.currentServer.map { it.serverInfo.name }.orElse(null)
        if (!player.isActive || backend == null || !bot.isVerificationBackendAllowed(backend)) {
            player.sendMessage(messages.minecraft("backend-required"))
            return
        }
        when (arguments) {
            emptyList<String>() -> issue(player, recovery = false, messages)
            listOf("status") -> showStatus(player, messages)
            listOf("recover") -> issue(player, recovery = true, messages)
            listOf("unlink", "confirm") -> unlink(player, messages)
            else -> player.sendMessage(messages.minecraft("usage"))
        }
    }

    private fun executeTelegram(
        player: Player,
        arguments: List<String>,
    ) {
        val bot = Velocity.telegramBot
        if (bot == null || !bot.isIdentityEnabled()) {
            player.sendMessage(Component.text("Привязка Telegram временно недоступна."))
            return
        }
        val backend = player.currentServer.map { it.serverInfo.name }.orElse(null)
        if (!player.isActive || backend == null || !bot.isIdentityBackendAllowed(backend)) {
            player.sendMessage(Component.text("Сначала войдите на игровой сервер."))
            return
        }
        when (arguments) {
            emptyList<String>() -> issueTelegram(player, bot)
            listOf("status") -> showTelegramStatus(player, bot)
            listOf("unlink", "confirm") -> unlinkTelegram(player, bot)
            else -> player.sendMessage(Component.text("Использование: /verify telegram [status|unlink confirm]"))
        }
    }

    private fun issueTelegram(
        player: Player,
        bot: ru.arc.telegram.TelegramBot,
    ) {
        Tasks.scheduler.runAsync {
            val response =
                when (val result = bot.issueIdentityChallenge(player.uniqueId, player.username)) {
                    is TelegramChallengeIssueResult.Issued ->
                        botIdentityMessage(
                            "minecraft-challenge",
                            mapOf(
                                "code" to result.code,
                                "bot_username" to bot.botUsername,
                                "minutes" to retryMinutes(result.expiresAt).toString(),
                            ),
                        )
                    is TelegramChallengeIssueResult.AlreadyLinked ->
                        botIdentityMessage(
                            "minecraft-telegram-already-linked",
                            mapOf("player_name" to result.link.playerName),
                        )
                    is TelegramChallengeIssueResult.RateLimited ->
                        botIdentityMessage(
                            "minecraft-rate-limited",
                            mapOf("minutes" to retryMinutes(result.retryAt).toString()),
                        )
                    TelegramChallengeIssueResult.Unavailable -> botIdentityMessage("minecraft-unavailable")
                }
            if (player.isActive) player.sendMessage(Component.text(response))
        }
    }

    private fun showTelegramStatus(
        player: Player,
        bot: ru.arc.telegram.TelegramBot,
    ) {
        Tasks.scheduler.runAsync {
            val link = bot.findIdentityByPlayer(player.uniqueId)
            val response =
                if (link == null) {
                    botIdentityMessage("minecraft-status-not-linked")
                } else {
                    botIdentityMessage(
                        "minecraft-status-linked",
                        mapOf(
                            "telegram_username" to (link.telegramUsername ?: link.telegramUserId.toString()),
                            "player_name" to link.playerName,
                        ),
                    )
                }
            if (player.isActive) player.sendMessage(Component.text(response))
        }
    }

    private fun unlinkTelegram(
        player: Player,
        bot: ru.arc.telegram.TelegramBot,
    ) {
        Tasks.scheduler.runAsync {
            val response =
                when (bot.unlinkIdentityByMinecraft(player.uniqueId)) {
                    is TelegramUnlinkResult.Unlinked -> botIdentityMessage("minecraft-unlink-success")
                    TelegramUnlinkResult.NotLinked -> botIdentityMessage("minecraft-unlink-not-linked")
                    TelegramUnlinkResult.Unavailable -> botIdentityMessage("minecraft-unavailable")
                }
            if (player.isActive) player.sendMessage(Component.text(response))
        }
    }

    private fun botIdentityMessage(
        key: String,
        values: Map<String, String> = emptyMap(),
    ): String = Velocity.telegramBot?.identityMessage(key, values) ?: "Привязка Telegram временно недоступна."

    private fun issue(
        player: Player,
        recovery: Boolean,
        messages: DiscordVerificationMessages,
    ) {
        Tasks.scheduler.runAsync {
            val bot = Velocity.discordBot ?: return@runAsync
            val inviteUrl = bot.verificationInviteUrl()
            if (inviteUrl == null || !DiscordVerificationConfig.validInviteUrl(inviteUrl)) {
                if (player.isActive) player.sendMessage(messages.minecraft("issue-failed-unchanged"))
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
                        messages.challenge(
                            code = result.code,
                            expiresInMinutes = retryMinutes(result.expiresAt),
                            recovery = recovery,
                        ),
                    )
                is DiscordChallengeIssueResult.AlreadyLinked ->
                    player.sendMessage(messages.minecraft("already-linked"))
                DiscordChallengeIssueResult.NotLinked ->
                    player.sendMessage(messages.minecraft("recovery-not-linked"))
                is DiscordChallengeIssueResult.RateLimited ->
                    player.sendMessage(
                        messages.minecraft(
                            "rate-limited",
                            "minutes" to retryMinutes(result.retryAt).toString(),
                        ),
                    )
                DiscordChallengeIssueResult.Unavailable ->
                    player.sendMessage(messages.minecraft("issue-failed-saved"))
            }
        }
    }

    private fun showStatus(
        player: Player,
        messages: DiscordVerificationMessages,
    ) {
        Tasks.scheduler.runAsync {
            val link = Velocity.discordBot?.findIdentityByPlayer(player.uniqueId)
            if (!player.isActive) return@runAsync
            player.sendMessage(
                if (link == null) {
                    messages.minecraft("status-not-linked")
                } else {
                    messages.minecraft("status-linked", "player_name" to link.playerName)
                },
            )
        }
    }

    private fun unlink(
        player: Player,
        messages: DiscordVerificationMessages,
    ) {
        Tasks.scheduler.runAsync {
            val bot = Velocity.discordBot ?: return@runAsync
            bot.unlinkIdentityByMinecraft(player.uniqueId).whenComplete { result, error ->
                if (!player.isActive) return@whenComplete
                val response =
                    when {
                        error != null -> messages.minecraft("unlink-failed-retry")
                        result is DiscordVerificationWorkflowResult.Unlinked -> messages.minecraft("unlink-success")
                        result is DiscordVerificationWorkflowResult.NotLinked -> messages.minecraft("unlink-not-linked")
                        result is DiscordVerificationWorkflowResult.RoleFailure -> messages.minecraft("unlink-role-failure")
                        else -> messages.minecraft("unlink-failed-saved")
                    }
                player.sendMessage(response)
            }
        }
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        val args = invocation.arguments()
        return when (args.size) {
            0, 1 -> listOf("status", "recover", "unlink", "telegram").filter { it.startsWith(args.firstOrNull().orEmpty(), true) }
            2 ->
                when {
                    args[0].equals("unlink", true) && "confirm".startsWith(args[1], true) -> listOf("confirm")
                    args[0].equals("telegram", true) ->
                        listOf("status", "unlink").filter { it.startsWith(args[1], true) }
                    else -> emptyList()
                }
            3 ->
                if (args[0].equals("telegram", true) && args[1].equals("unlink", true) &&
                    "confirm".startsWith(args[2], true)
                ) {
                    listOf("confirm")
                } else {
                    emptyList()
                }
            else -> emptyList()
        }
    }

    override fun suggestAsync(invocation: SimpleCommand.Invocation): CompletableFuture<List<String>> =
        CompletableFuture.completedFuture(suggest(invocation))

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean = true

    private fun messages(): DiscordVerificationMessages =
        Velocity.discordBot?.verificationMessages() ?: DiscordVerificationMessages.load()

    private fun retryMinutes(retryAt: Long): Long =
        TimeUnit.MILLISECONDS.toMinutes((retryAt - System.currentTimeMillis()).coerceAtLeast(0) + 59_999)
            .coerceAtLeast(1)
}
