package ru.arc.discord

import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

internal class DiscordVerificationListener(
    private val config: DiscordVerificationConfig,
    private val verification: DiscordVerificationService,
) : ListenerAdapter() {
    private val messages = config.messages

    fun registerCommands(snapshot: DiscordSessionSnapshot) {
        if (!config.enabled) return
        val guild = snapshot.jda.getGuildById(config.guildId)
        if (guild == null) {
            log.error("Discord verification guild is not available")
            return
        }
        guild.upsertCommand(
            Commands.slash("verify", messages.discordCommandDescription("verify-description"))
                .addOption(
                    OptionType.STRING,
                    "code",
                    messages.discordCommandDescription("verify-code-description"),
                    false,
                ),
        ).queue(
            { log.info("Discord /verify command registered") },
            { error -> log.error("Could not register Discord /verify command", error) },
        )
        guild.upsertCommand(
            Commands.slash("unlink", messages.discordCommandDescription("unlink-description"))
                .addOption(
                    OptionType.BOOLEAN,
                    "confirm",
                    messages.discordCommandDescription("unlink-confirm-description"),
                    true,
                ),
        ).queue(
            { log.info("Discord /unlink command registered") },
            { error -> log.error("Could not register Discord /unlink command", error) },
        )
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (!config.enabled || event.guild?.id != config.guildId) return
        when (event.name) {
            "verify" -> handleVerify(event)
            "unlink" -> handleUnlink(event)
        }
    }

    private fun handleVerify(event: SlashCommandInteractionEvent) {
        val code = event.getOption("code")?.asString?.trim()
        if (code.isNullOrEmpty()) {
            if (!verification.isAvailable()) {
                event.reply(messages.discord("verification-unavailable-saved"))
                    .setEphemeral(true)
                    .queue()
                return
            }
            val link = verification.findByDiscordUserId(event.user.id)
            val text =
                if (link == null) {
                    messages.discord("status-not-linked")
                } else {
                    messages.discord("status-linked", "player_name" to link.playerName)
                }
            event.reply(text).setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue { hook ->
            verification.completeFromDiscord(code, event.user.id).whenComplete { result, error ->
                val message =
                    if (error != null) {
                        log.warn("Discord verification workflow failed: {}", error.javaClass.simpleName)
                        messages.discord("verify-failed")
                    } else {
                        resultMessage(result)
                    }
                hook.editOriginal(message).queue()
            }
        }
    }

    private fun handleUnlink(event: SlashCommandInteractionEvent) {
        if (event.getOption("confirm")?.asBoolean != true) {
            event.reply(messages.discord("unlink-cancelled")).setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue { hook ->
            verification.unlinkByDiscord(event.user.id).whenComplete { result, error ->
                val message =
                    if (error != null) {
                        log.warn("Discord unlink workflow failed: {}", error.javaClass.simpleName)
                        messages.discord("unlink-failed")
                    } else {
                        resultMessage(result)
                    }
                hook.editOriginal(message).queue()
            }
        }
    }

    internal fun resultMessage(result: DiscordVerificationWorkflowResult): String =
        when (result) {
            is DiscordVerificationWorkflowResult.Verified ->
                if (result.reconciliation.successful) {
                    if (result.reconciliation.nicknameSkipped) {
                        messages.discord("verified-nickname-skipped", "player_name" to result.link.playerName)
                    } else {
                        messages.discord("verified", "player_name" to result.link.playerName)
                    }
                } else {
                    messages.discord("verified-role-sync-failed", "player_name" to result.link.playerName)
                }
            is DiscordVerificationWorkflowResult.Recovered ->
                if (result.reconciliation.successful) {
                    if (result.reconciliation.nicknameSkipped) {
                        messages.discord("recovered-nickname-skipped", "player_name" to result.link.playerName)
                    } else {
                        messages.discord("recovered", "player_name" to result.link.playerName)
                    }
                } else {
                    messages.discord("recovered-role-sync-failed", "player_name" to result.link.playerName)
                }
            is DiscordVerificationWorkflowResult.Unlinked -> messages.discord("unlinked")
            is DiscordVerificationWorkflowResult.RateLimited ->
                messages.discord("rate-limited", "minutes" to retryMinutes(result.retryAt).toString())
            DiscordVerificationWorkflowResult.InvalidOrExpired ->
                messages.discord("invalid-or-expired")
            DiscordVerificationWorkflowResult.MinecraftAlreadyLinked ->
                messages.discord("minecraft-already-linked")
            DiscordVerificationWorkflowResult.DiscordAlreadyLinked ->
                messages.discord("discord-already-linked")
            DiscordVerificationWorkflowResult.NotLinked -> messages.discord("not-linked")
            DiscordVerificationWorkflowResult.RecoveryCancelled -> messages.discord("recovery-cancelled")
            is DiscordVerificationWorkflowResult.RoleFailure ->
                messages.discord("role-failure")
            DiscordVerificationWorkflowResult.Conflict ->
                messages.discord("conflict")
            DiscordVerificationWorkflowResult.Unavailable ->
                messages.discord("unavailable")
        }

    private fun retryMinutes(retryAt: Long): Long =
        TimeUnit.MILLISECONDS.toMinutes((retryAt - System.currentTimeMillis()).coerceAtLeast(0) + 59_999)
            .coerceAtLeast(1)

    companion object {
        private val log = LoggerFactory.getLogger(DiscordVerificationListener::class.java)
    }
}
