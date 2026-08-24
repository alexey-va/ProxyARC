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
    fun registerCommands(snapshot: DiscordSessionSnapshot) {
        if (!config.enabled) return
        val guild = snapshot.jda.getGuildById(config.guildId)
        if (guild == null) {
            log.error("Discord verification guild is not available")
            return
        }
        guild.upsertCommand(
            Commands.slash("verify", "Связать Discord с аккаунтом Minecraft")
                .addOption(OptionType.STRING, "code", "Одноразовый код из команды /verify", false),
        ).queue(
            { log.info("Discord /verify command registered") },
            { error -> log.error("Could not register Discord /verify command", error) },
        )
        guild.upsertCommand(
            Commands.slash("unlink", "Отвязать аккаунт Minecraft")
                .addOption(OptionType.BOOLEAN, "confirm", "Подтвердить отвязку", true),
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
                event.reply("Discord • Проверка временно недоступна. Данные сохранены.")
                    .setEphemeral(true)
                    .queue()
                return
            }
            val link = verification.findByDiscordUserId(event.user.id)
            val text =
                if (link == null) {
                    "Discord • Аккаунт не связан. Зайдите на сервер и получите код командой /verify."
                } else {
                    "Discord • Связан аккаунт Minecraft: ${link.playerName}."
                }
            event.reply(text).setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue { hook ->
            verification.completeFromDiscord(code, event.user.id).whenComplete { result, error ->
                val message =
                    if (error != null) {
                        log.warn("Discord verification workflow failed: {}", error.javaClass.simpleName)
                        "Discord • Не удалось завершить проверку. Попробуйте ещё раз позже."
                    } else {
                        resultMessage(result)
                    }
                hook.editOriginal(message).queue()
            }
        }
    }

    private fun handleUnlink(event: SlashCommandInteractionEvent) {
        if (event.getOption("confirm")?.asBoolean != true) {
            event.reply("Discord • Отвязка отменена.").setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue { hook ->
            verification.unlinkByDiscord(event.user.id).whenComplete { result, error ->
                val message =
                    if (error != null) {
                        log.warn("Discord unlink workflow failed: {}", error.javaClass.simpleName)
                        "Discord • Не удалось отвязать аккаунт. Попробуйте ещё раз позже."
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
                    "Discord • Аккаунт Minecraft ${result.link.playerName} подтверждён. Роли и ник синхронизированы."
                } else {
                    "Discord • Связь с ${result.link.playerName} сохранена, но роли пока не синхронизированы."
                }
            is DiscordVerificationWorkflowResult.Recovered ->
                if (result.reconciliation.successful) {
                    "Discord • Доступ к аккаунту ${result.link.playerName} восстановлен."
                } else {
                    "Discord • Доступ к ${result.link.playerName} восстановлен, но роли пока не синхронизированы."
                }
            is DiscordVerificationWorkflowResult.Unlinked -> "Discord • Аккаунт отвязан. Управляемые роли сняты."
            is DiscordVerificationWorkflowResult.RateLimited ->
                "Discord • Слишком много попыток. Повторите через ${retryMinutes(result.retryAt)} мин."
            DiscordVerificationWorkflowResult.InvalidOrExpired ->
                "Discord • Код неверный или уже истёк. Получите новый код на сервере."
            DiscordVerificationWorkflowResult.MinecraftAlreadyLinked ->
                "Discord • Этот аккаунт Minecraft уже связан. Для переноса используйте /verify recover на сервере."
            DiscordVerificationWorkflowResult.DiscordAlreadyLinked ->
                "Discord • Этот Discord уже связан с другим аккаунтом Minecraft."
            DiscordVerificationWorkflowResult.NotLinked -> "Discord • Аккаунт не связан."
            is DiscordVerificationWorkflowResult.RoleFailure ->
                "Discord • Операция остановлена: бот пока не может безопасно изменить роли."
            DiscordVerificationWorkflowResult.Conflict ->
                "Discord • Состояние аккаунта изменилось. Получите новый код и повторите операцию."
            DiscordVerificationWorkflowResult.Unavailable ->
                "Discord • Проверка временно недоступна. Данные не изменены."
        }

    private fun retryMinutes(retryAt: Long): Long =
        TimeUnit.MILLISECONDS.toMinutes((retryAt - System.currentTimeMillis()).coerceAtLeast(0) + 59_999)
            .coerceAtLeast(1)

    companion object {
        private val log = LoggerFactory.getLogger(DiscordVerificationListener::class.java)
    }
}
