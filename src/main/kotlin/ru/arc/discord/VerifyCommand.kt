package ru.arc.discord

import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import ru.arc.core.Tasks
import ru.arc.telegram.TelegramChallengeIssueResult
import ru.arc.telegram.TelegramConfig
import ru.arc.telegram.TelegramIdentityLink
import ru.arc.telegram.TelegramUnlinkResult
import ru.arc.telegram.TelegramVerificationMessages
import ru.arc.velocity.Velocity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal enum class VerificationPlatform {
    DISCORD,
    TELEGRAM,
}

internal data class VerificationInvocation(
    val platform: VerificationPlatform,
    val arguments: List<String>,
)

internal fun telegramAccountLabel(link: TelegramIdentityLink): String =
    link.telegramUsername
        ?.trim()
        ?.removePrefix("@")
        ?.takeIf(String::isNotBlank)
        ?.let { "@$it" }
        ?: link.telegramDisplayName.trim().takeIf(String::isNotBlank)
        ?: "Telegram ID ${link.telegramUserId}"

internal fun discordAccountLabel(
    username: String?,
    discordUserId: String,
): String =
    username
        ?.trim()
        ?.removePrefix("@")
        ?.takeIf(String::isNotBlank)
        ?.let { "@$it" }
        ?: "Discord ID $discordUserId"

internal fun resolveVerificationInvocation(
    defaultPlatform: VerificationPlatform?,
    arguments: List<String>,
): VerificationInvocation {
    if (defaultPlatform != null) return VerificationInvocation(defaultPlatform, arguments)
    return when (arguments.firstOrNull()) {
        "discord" -> VerificationInvocation(VerificationPlatform.DISCORD, arguments.drop(1))
        "telegram" -> VerificationInvocation(VerificationPlatform.TELEGRAM, arguments.drop(1))
        else -> VerificationInvocation(VerificationPlatform.DISCORD, arguments)
    }
}

internal class VerifyCommand(
    private val defaultPlatform: VerificationPlatform? = null,
) : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val arguments = invocation.arguments().map(String::lowercase)
        val request = resolveVerificationInvocation(defaultPlatform, arguments)
        val player = invocation.source() as? Player
        if (player == null) {
            val response =
                when (request.platform) {
                    VerificationPlatform.DISCORD -> messages().minecraft("only-player")
                    VerificationPlatform.TELEGRAM -> telegramMessages().minecraft("only-player")
                }
            invocation.source().sendMessage(response)
            return
        }
        when (request.platform) {
            VerificationPlatform.DISCORD -> executeDiscord(player, request.arguments, messages())
            VerificationPlatform.TELEGRAM -> executeTelegram(player, request.arguments)
        }
    }

    private fun executeDiscord(
        player: Player,
        arguments: List<String>,
        messages: DiscordVerificationMessages,
    ) {
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
        val messages = telegramMessages()
        val bot = Velocity.telegramBot
        if (bot == null || !bot.isIdentityEnabled()) {
            player.sendMessage(messages.minecraft("unavailable"))
            return
        }
        val backend = player.currentServer.map { it.serverInfo.name }.orElse(null)
        if (!player.isActive || backend == null || !bot.isIdentityBackendAllowed(backend)) {
            player.sendMessage(messages.minecraft("backend-required"))
            return
        }
        when (arguments) {
            emptyList<String>() -> issueTelegram(player, bot, messages)
            listOf("status") -> showTelegramStatus(player, bot, messages)
            listOf("unlink", "confirm") -> unlinkTelegram(player, bot, messages)
            else -> player.sendMessage(messages.minecraft("usage"))
        }
    }

    private fun issueTelegram(
        player: Player,
        bot: ru.arc.telegram.TelegramBot,
        messages: TelegramVerificationMessages,
    ) {
        Tasks.scheduler.runAsync {
            val response =
                when (val result = bot.issueIdentityChallenge(player.uniqueId, player.username)) {
                    is TelegramChallengeIssueResult.Issued ->
                        messages.challenge(result.code, retryMinutes(result.expiresAt))
                    is TelegramChallengeIssueResult.AlreadyLinked ->
                        messages.minecraft("already-linked", "player_name" to telegramAccountLabel(result.link))
                    is TelegramChallengeIssueResult.RateLimited ->
                        messages.minecraft(
                            "rate-limited",
                            "minutes" to retryMinutes(result.retryAt).toString(),
                        )
                    TelegramChallengeIssueResult.Unavailable -> messages.minecraft("unavailable")
                }
            if (player.isActive) player.sendMessage(response)
        }
    }

    private fun showTelegramStatus(
        player: Player,
        bot: ru.arc.telegram.TelegramBot,
        messages: TelegramVerificationMessages,
    ) {
        Tasks.scheduler.runAsync {
            val link = bot.findIdentityByPlayer(player.uniqueId)
            val response =
                if (link == null) {
                    messages.minecraft("status-not-linked")
                } else {
                    messages.minecraft(
                        "status-linked",
                        "telegram_username" to (link.telegramUsername?.let { "@$it" } ?: link.telegramUserId.toString()),
                        "player_name" to link.playerName,
                    )
                }
            if (player.isActive) player.sendMessage(response)
        }
    }

    private fun unlinkTelegram(
        player: Player,
        bot: ru.arc.telegram.TelegramBot,
        messages: TelegramVerificationMessages,
    ) {
        Tasks.scheduler.runAsync {
            val response =
                when (bot.unlinkIdentityByMinecraft(player.uniqueId)) {
                    is TelegramUnlinkResult.Unlinked -> messages.minecraft("unlink-success")
                    TelegramUnlinkResult.NotLinked -> messages.minecraft("unlink-not-linked")
                    TelegramUnlinkResult.Unavailable -> messages.minecraft("unavailable")
                }
            if (player.isActive) player.sendMessage(response)
        }
    }

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
                    player.sendMessage(
                        messages.minecraft(
                            "already-linked",
                            "player_name" to bot.identityAccountLabel(result.link),
                        ),
                    )
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
            val bot = Velocity.discordBot
            val link = bot?.findIdentityByPlayer(player.uniqueId)
            if (!player.isActive) return@runAsync
            player.sendMessage(
                if (link == null) {
                    messages.minecraft("status-not-linked")
                } else {
                    messages.minecraft("status-linked", "player_name" to bot.identityAccountLabel(link))
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
        val args = invocation.arguments().toList()
        if (defaultPlatform != null) return suggestPlatform(defaultPlatform, args)
        if (args.size <= 1) {
            return listOf("discord", "telegram", "status", "recover", "unlink")
                .filter { it.startsWith(args.firstOrNull().orEmpty(), true) }
        }
        return when {
            args[0].equals("discord", true) -> suggestPlatform(VerificationPlatform.DISCORD, args.drop(1))
            args[0].equals("telegram", true) -> suggestPlatform(VerificationPlatform.TELEGRAM, args.drop(1))
            else -> suggestPlatform(VerificationPlatform.DISCORD, args)
        }
    }

    private fun suggestPlatform(
        platform: VerificationPlatform,
        args: List<String>,
    ): List<String> =
        when (args.size) {
            0, 1 ->
                when (platform) {
                    VerificationPlatform.DISCORD -> listOf("status", "recover", "unlink")
                    VerificationPlatform.TELEGRAM -> listOf("status", "unlink")
                }.filter { it.startsWith(args.firstOrNull().orEmpty(), true) }
            2 ->
                if (args[0].equals("unlink", true) && "confirm".startsWith(args[1], true)) {
                    listOf("confirm")
                } else {
                    emptyList()
                }
            else -> emptyList()
        }

    override fun suggestAsync(invocation: SimpleCommand.Invocation): CompletableFuture<List<String>> =
        CompletableFuture.completedFuture(suggest(invocation))

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean = true

    private fun messages(): DiscordVerificationMessages =
        Velocity.discordBot?.verificationMessages() ?: DiscordVerificationMessages.load()

    private fun telegramMessages(): TelegramVerificationMessages =
        Velocity.telegramBot?.verificationMessages() ?: TelegramVerificationMessages(TelegramConfig.load())

    private fun retryMinutes(retryAt: Long): Long =
        TimeUnit.MILLISECONDS.toMinutes((retryAt - System.currentTimeMillis()).coerceAtLeast(0) + 59_999)
            .coerceAtLeast(1)
}
