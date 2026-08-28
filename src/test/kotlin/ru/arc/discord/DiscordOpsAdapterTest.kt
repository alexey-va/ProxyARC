package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import ru.arc.ops.DiscordGuildMutation
import ru.arc.ops.DiscordGuildMutationRequest
import ru.arc.ops.DiscordMessageMutation
import ru.arc.ops.DiscordMessageMutationRequest
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture

class DiscordOpsAdapterTest : FreeSpec({
    "a configured root channel is allowed" {
        DiscordOpsAdapter.isAllowed(
            channelId = "100000000000000001",
            parentChannelId = null,
            allowedChannelIds = setOf("100000000000000001"),
        ) shouldBe true
    }

    "a thread inherits access from its configured parent" {
        DiscordOpsAdapter.isAllowed(
            channelId = "200000000000000002",
            parentChannelId = "100000000000000001",
            allowedChannelIds = setOf("100000000000000001"),
        ) shouldBe true
    }

    "an unrelated channel is denied" {
        DiscordOpsAdapter.isAllowed(
            channelId = "200000000000000002",
            parentChannelId = "300000000000000003",
            allowedChannelIds = setOf("100000000000000001"),
        ) shouldBe false
    }

    "wildcard allows every guild channel" {
        DiscordOpsAdapter.isAllowed(
            channelId = "200000000000000002",
            parentChannelId = null,
            allowedChannelIds = setOf("*"),
        ) shouldBe true
    }

    "public thread payload does not read private-only invitable state" {
        val thread = mockk<ThreadChannel>()
        every { thread.type } returns ChannelType.GUILD_PUBLIC_THREAD

        DiscordOpsAdapter.threadInvitable(thread) shouldBe null

        verify(exactly = 0) { thread.isInvitable }
    }

    "private thread payload exposes invitable state" {
        val thread = mockk<ThreadChannel>()
        every { thread.type } returns ChannelType.GUILD_PRIVATE_THREAD
        every { thread.isInvitable } returns true

        DiscordOpsAdapter.threadInvitable(thread) shouldBe true
    }

    "community channel update reasserts the existing community feature" {
        val request =
            DiscordGuildMutationRequest(
                operation = DiscordGuildMutation.UPDATE,
                guildId = "100000000000000001",
                rulesChannelId = "200000000000000002",
            )

        DiscordOpsAdapter.shouldReassertCommunityFeature(request, setOf("COMMUNITY", "NEWS")) shouldBe true
    }

    "unrelated guild update does not touch community features" {
        val request =
            DiscordGuildMutationRequest(
                operation = DiscordGuildMutation.UPDATE,
                guildId = "100000000000000001",
                name = "RusCrafting",
            )

        DiscordOpsAdapter.shouldReassertCommunityFeature(request, setOf("COMMUNITY", "NEWS")) shouldBe false
    }

    "send disables mentions and replied-user ping" {
        val channelId = "100000000000000001"
        val replyId = "200000000000000002"
        val allowedMentionId = "400000000000000004"
        val jda = mockk<JDA>()
        val channel = mockk<TextChannel>()
        val action = mockk<MessageCreateAction>()
        val sent = mockk<Message>(relaxed = true)
        every { jda.getTextChannelById(channelId) } returns channel
        every { channel.sendMessage(any<MessageCreateData>()) } returns action
        every { action.setAllowedMentions(emptySet()) } returns action
        every { action.mentionRepliedUser(false) } returns action
        every { action.mentionUsers(setOf(allowedMentionId)) } returns action
        every { action.setMessageReference(replyId) } returns action
        every { action.submit() } returns CompletableFuture.completedFuture(sent)
        every { sent.id } returns "300000000000000003"
        every { sent.channelId } returns channelId
        every { sent.timeCreated } returns OffsetDateTime.parse("2026-08-07T15:00:00+03:00")
        every { sent.timeEdited } returns null
        every { sent.jumpUrl } returns
            "https://discord.com/channels/1/$channelId/300000000000000003"
        val adapter = DiscordOpsAdapter({ jda }, { emptyMap() })

        val result =
            adapter.mutateMessage(
                DiscordMessageMutationRequest(
                    operation = DiscordMessageMutation.SEND,
                    channelId = channelId,
                    content = "Проверка",
                    replyToMessageId = replyId,
                    allowedUserMentionIds = setOf(allowedMentionId),
                ),
            ).join()

        result["id"] shouldBe "300000000000000003"
        verify(exactly = 1) { action.setAllowedMentions(emptySet()) }
        verify(exactly = 1) { action.mentionRepliedUser(false) }
        verify(exactly = 1) { action.mentionUsers(setOf(allowedMentionId)) }
        verify(exactly = 1) { action.setMessageReference(replyId) }
    }
})
