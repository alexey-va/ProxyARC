package ru.arc.staff

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class StaffDiscordListenerTest : FreeSpec({
    "native shortcuts and mc subcommands share required argument definitions" {
        val commands = mutableListOf<CommandData>()
        val guild = mockk<Guild>()
        val api = mockk<JDA>()
        val config = mockk<StaffBridgeConfig> {
            every { guildId } returns "123456789012345678"
            every { commandChannelId } returns "1563455633569751070"
        }
        every { api.getGuildById("123456789012345678") } returns guild
        every { guild.upsertCommand(capture(commands)) } returns mockk(relaxed = true)

        StaffDiscordListener.register(api, config)

        commands.map { it.name }.toSet() shouldBe setOf("mc", "kick", "ban", "mute")
        val mc = commands.filterIsInstance<SlashCommandData>().single { it.name == "mc" }
        mc.subcommands.map { it.name }.toSet() shouldBe setOf("kick", "ban", "mute", "unmute", "unban", "command")
        StaffCommands.SHORTCUTS.forEach { name ->
            val standalone = commands.filterIsInstance<SlashCommandData>().single { it.name == name }
            val nested = mc.subcommands.single { it.name == name }
            val expected = if (name == "kick") listOf("player", "reason") else listOf("player", "duration", "reason")
            standalone.options.map { it.name } shouldBe expected
            nested.options.map { it.name } shouldBe expected
            standalone.options.all { it.isRequired } shouldBe true
            nested.options.all { it.isRequired } shouldBe true
            standalone.toData().getString("name") shouldBe name
        }
    }

    "native moderation commands preserve the event identity and shared pipeline behind channel guild and ACL gates" {
        val requests = mutableListOf<StaffRequest>()
        withDiscordStaffRuntime(requests) { runtime ->
            val listener = StaffDiscordListener()
            val event = mockk<SlashCommandInteractionEvent>(relaxed = true)
            val reply = mockk<ReplyCallbackAction>(relaxed = true)
            every { event.guild!!.id } returns runtime.config.guildId
            every { event.channel.id } returns runtime.config.commandChannelId
            every { event.user.isBot } returns false
            every { event.user.id } returns "777"
            every { event.member } returns mockk()
            every { event.id } returns "12345"
            every { event.timeCreated } returns OffsetDateTime.parse("2026-10-27T00:00:00Z")
            every { event.getOption("player")!!.asString } returns "Player"
            every { event.getOption("duration")!!.asString } returns "7d"
            every { event.getOption("reason")!!.asString } returns "Нарушение правил"
            every { event.deferReply(true) } returns reply
            every { reply.queue(any<Consumer<InteractionHook>>()) } answers {
                firstArg<Consumer<InteractionHook>>().accept(mockk(relaxed = true))
            }
            listOf("kick", "ban", "mute").forEach { action ->
                every { event.name } returns action
                listener.onSlashCommandInteraction(event)
                val expected = if (action == "kick") "$action Player Нарушение правил" else "$action Player 7d Нарушение правил"
                requests.last() shouldBe StaffRequest("discord:12345", "discord", "777", event.timeCreated.toInstant().toEpochMilli(), expected)
                every { event.name } returns "mc"
                every { event.subcommandName } returns action
                listener.onSlashCommandInteraction(event)
                requests.last().input shouldBe expected
            }
            requests.size shouldBe 6
            every { event.name } returns "kick"
            every { event.guild!!.id } returns "other-guild"
            listener.onSlashCommandInteraction(event)
            every { event.guild!!.id } returns runtime.config.guildId
            every { event.channel.id } returns "other-channel"
            listener.onSlashCommandInteraction(event)
            every { event.channel.id } returns runtime.config.commandChannelId
            every { runtime.discord.channel(any(), false) } returns null
            listener.onSlashCommandInteraction(event)
            requests.size shouldBe 6
        }
    }

    "text aliases are consumed before public chat even when denied or disabled" {
        val requests = mutableListOf<StaffRequest>()
        withDiscordStaffRuntime(requests) { runtime ->
            val event = mockk<MessageReceivedEvent>(relaxed = true)
            every { event.isFromGuild } returns true
            every { event.guild.id } returns runtime.config.guildId
            every { event.channel.id } returns runtime.config.commandChannelId
            every { event.author.isBot } returns false
            every { event.message.isWebhookMessage } returns false
            every { event.author.id } returns "777"
            every { event.messageId } returns "12345"
            every { event.message.timeCreated } returns OffsetDateTime.parse("2026-10-27T00:00:00Z")
            listOf("!kick Player Причина", "!ban Player 7d Причина", "!mute Player 10m Причина", "!mc kick Player Причина").forEach { text ->
                every { event.message.contentRaw } returns text
                StaffDiscordListener.consumeMessage(event) shouldBe true
                requests.last().input shouldBe if (text.startsWith("!mc ")) text.removePrefix("!mc ") else text.drop(1)
                StaffDiscordListener.isCommandMessage(text.replaceFirst(' ', '\n')) shouldBe true
            }
            every { event.channel.id } returns "public-channel"
            StaffDiscordListener.consumeMessage(event) shouldBe true
            every { StaffBridgeModule.runtime } returns null
            StaffDiscordListener.consumeMessage(event) shouldBe true
            requests.size shouldBe 4
            StaffDiscordListener.isCommandMessage("!kickoff Player") shouldBe false
            StaffDiscordListener.isCommandMessage("ordinary chat") shouldBe false
        }
    }
})

private fun withDiscordStaffRuntime(requests: MutableList<StaffRequest>, block: (StaffBridgeRuntime) -> Unit) {
    val staffConfig = mockk<StaffBridgeConfig> {
        every { guildId } returns "123456789012345678"
        every { commandChannelId } returns "1563455633569751070"
    }
    val gateway = mockk<StaffDiscordGateway>(relaxed = true)
    every { gateway.channel(any(), false) } returns mockk()
    val service = mockk<StaffCommandService>()
    every { service.execute(capture(requests)) } answers { CompletableFuture<String>() }
    val runtime = mockk<StaffBridgeRuntime> {
        every { config } returns staffConfig
        every { discord } returns gateway
        every { commands } returns service
    }
    mockkObject(StaffBridgeModule)
    every { StaffBridgeModule.runtime } returns runtime
    try {
        block(runtime)
    } finally {
        unmockkObject(StaffBridgeModule)
    }
}
