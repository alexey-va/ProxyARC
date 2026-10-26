package ru.arc.staff

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.slf4j.LoggerFactory

/** Discord's role restriction improves discovery; linked Minecraft authorization remains the execution gate. */
internal class StaffDiscordListener : ListenerAdapter() {
    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.name != "mc" && event.name !in StaffCommands.SHORTCUTS) return
        val runtime = StaffBridgeModule.runtime
        if (runtime == null || event.guild?.id != runtime.config.guildId || event.user.isBot || event.member == null) {
            event.reply("Служебная команда недоступна в этом канале.").setEphemeral(true).queue()
            return
        }
        val adminOnly = event.channel.id != runtime.config.commandChannelId || runtime.discord.channel(event.channel.id, false) == null
        val input = when (val action = if (event.name == "mc") event.subcommandName else event.name) {
            "kick" -> "$action ${event.getOption("player")?.asString.orEmpty()} ${event.getOption("reason")?.asString.orEmpty()}"
            "mute", "ban" -> "$action ${event.getOption("player")?.asString.orEmpty()} ${event.getOption("duration")?.asString.orEmpty()} ${event.getOption("reason")?.asString.orEmpty()}"
            "unmute", "unban" -> "$action ${event.getOption("player")?.asString.orEmpty()}"
            "command" -> "command ${event.getOption("server")?.asString.orEmpty()} ${event.getOption("text")?.asString.orEmpty()}"
            else -> ""
        }
        event.deferReply(true).queue { hook ->
            runtime.commands.execute(StaffRequest("discord:${event.id}", "discord", event.user.id,
                event.timeCreated.toInstant().toEpochMilli(), input, adminOnly = adminOnly)).thenAccept { output ->
                hook.editOriginal(StaffDiscordGateway.literal(output)).setAllowedMentions(emptyList()).queue()
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(StaffDiscordListener::class.java)

        fun register(api: JDA, config: StaffBridgeConfig) {
            if (config.guildId.isEmpty()) return
            val command = Commands.slash("mc", "Модерация Minecraft и служебные команды")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
            val shortcuts = StaffCommands.SHORTCUTS.map { name ->
                val description = when (name) {
                    "kick" -> "Отключить игрока от сервера"
                    "ban" -> "Заблокировать игрока"
                    else -> "Ограничить чат игрока"
                }
                val subcommand = SubcommandData(name, description)
                    .addOption(OptionType.STRING, "player", "Игровой ник", true)
                if (name != "kick") {
                    subcommand.addOption(OptionType.STRING, "duration", "Срок: 10m, 2h, 7d или permanent", true)
                }
                subcommand.addOption(OptionType.STRING, "reason", "Причина", true)
                command.addSubcommands(subcommand)
                Commands.slash(name, description).addOptions(subcommand.options)
                    .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
            }
            listOf("unmute" to "Снять ограничение чата", "unban" to "Снять блокировку").forEach { (name, description) ->
                command.addSubcommands(SubcommandData(name, description).addOption(OptionType.STRING, "player", "Игровой ник", true))
            }
            command.addSubcommands(SubcommandData("command", "Выполнить команду с вашими правами")
                .addOption(OptionType.STRING, "server", "proxy или игровой сервер; серверная консоль только для администраторов", true)
                .addOption(OptionType.STRING, "text", "Команда без начального слеша", true))
            val guild = api.getGuildById(config.guildId) ?: return
            (listOf(command) + shortcuts).forEach { guild.upsertCommand(it).queue() }
        }

        fun isCommandMessage(text: String): Boolean {
            val verb = text.takeWhile { !it.isWhitespace() }
            return verb == "!mc" || (verb.startsWith('!') && verb.drop(1) in StaffCommands.SHORTCUTS)
        }

        /** Consume before public chat/portal/sync processing, including denied or disabled command attempts. */
        fun consumeMessage(event: MessageReceivedEvent): Boolean {
            val text = event.message.contentRaw
            if (!isCommandMessage(text)) return StaffBridgeModule.protectedDiscordChannel(event.channel.id)
            val runtime = StaffBridgeModule.runtime ?: return true
            if (event.author.isBot || event.message.isWebhookMessage) return true
            if (event.isFromGuild) {
                if (event.guild.id != runtime.config.guildId || event.member == null) return true
            } else if (event.channel.type != ChannelType.PRIVATE) return true
            val privateCommandChannel = event.isFromGuild && event.channel.id == runtime.config.commandChannelId &&
                runtime.discord.channel(event.channel.id, false) != null
            runtime.commands.execute(StaffRequest("discord:${event.messageId}", "discord", event.author.id,
                event.message.timeCreated.toInstant().toEpochMilli(),
                if (text.takeWhile { !it.isWhitespace() } == "!mc") text.removePrefix("!mc").trim() else text.drop(1),
                adminOnly = !privateCommandChannel,
            )).thenAccept { result ->
                if (privateCommandChannel) {
                    runtime.discord.reply(event.channel.id, result)
                } else {
                    event.author.openPrivateChannel().submit().thenCompose { channel ->
                        channel.sendMessage(StaffDiscordGateway.literal(result.take(1800))).setAllowedMentions(emptyList()).submit()
                    }.whenComplete { _, failure ->
                        if (failure != null) log.warn("Staff Discord private reply failed user={} event={} cause={}",
                            event.author.id, event.messageId, failure.javaClass.simpleName)
                    }
                }
            }
            return true
        }
    }
}
