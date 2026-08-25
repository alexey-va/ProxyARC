package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction
import net.dv8tion.jda.api.requests.restaction.CacheRestAction
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DiscordRoleServiceTest : FreeSpec({
    "desired roles are exact and limited to the configured allowlist" {
        val config = verificationConfig(Files.createTempDirectory("discord-role-policy"))
        val service =
            DiscordRoleService(
                DiscordSession(),
                config,
                DiscordRoleFactsProvider { _, _ -> CompletableFuture.completedFuture(DiscordRoleFacts(emptySet(), emptySet())) },
            )

        service.desiredRoleIds(
            DiscordRoleFacts(
                groups = setOf("vip", "helper", "some-unmanaged-group"),
                permissions = emptySet(),
            ),
        ) shouldContainExactlyInAnyOrder
            setOf(
                config.verifiedRoleId,
                config.playerRoleId,
                "1083480822818029608",
                "1079927708743643156",
            )
    }

    "permission facts can grant VIP without a matching group" {
        val config = verificationConfig(Files.createTempDirectory("discord-role-permission"))
        val service =
            DiscordRoleService(
                DiscordSession(),
                config,
                DiscordRoleFactsProvider { _, _ -> CompletableFuture.completedFuture(DiscordRoleFacts(emptySet(), emptySet())) },
            )

        service.desiredRoleIds(DiscordRoleFacts(emptySet(), setOf("arc.vip"))) shouldContainExactlyInAnyOrder
            setOf(config.verifiedRoleId, config.playerRoleId, "1083480822818029608")
    }

    "a disabled policy stays managed but is excluded from desired roles" {
        val config =
            verificationConfig(Files.createTempDirectory("discord-role-disabled-policy")) { raw ->
                raw.setBoolean("roles.policies.helper.enabled", false)
            }
        val service =
            DiscordRoleService(
                DiscordSession(),
                config,
                DiscordRoleFactsProvider { _, _ -> CompletableFuture.completedFuture(DiscordRoleFacts(emptySet(), emptySet())) },
            )

        config.managedRoleIds.contains("1079927708743643156") shouldBe true
        service.desiredRoleIds(DiscordRoleFacts(setOf("helper"), emptySet())) shouldContainExactlyInAnyOrder
            setOf(config.verifiedRoleId, config.playerRoleId)
    }

    "an unmodifiable member still receives every manageable role while nickname sync is skipped" {
        val root = Files.createTempDirectory("discord-owner-role-reconcile")
        val config = verificationConfig(root)
        val session = DiscordSession()
        val jda = mockk<JDA>()
        val guild = mockk<Guild>()
        val selfMember = mockk<SelfMember>()
        val member = mockk<Member>()
        val memberAction = mockk<CacheRestAction<Member>>()
        val roleAction = mockk<AuditableRestAction<Void>>()
        val roles = config.managedRoleIds.associateWith(::role)
        val link = DiscordIdentityLink(UUID.randomUUID(), "GrocerMC", "123456789012345678", 1, 1)
        val completedRoleAction = CompletableFuture<Void>().also { it.complete(null) }

        every { jda.getGuildById(config.guildId) } returns guild
        every { guild.selfMember } returns selfMember
        every { guild.getRoleById(any<String>()) } answers { roles[firstArg()] }
        every { guild.retrieveMemberById(link.discordUserId) } returns memberAction
        every { memberAction.submit() } returns CompletableFuture.completedFuture(member)
        every { member.roles } returns emptyList()
        every { member.nickname } returns null
        every { selfMember.canInteract(member) } returns false
        every { selfMember.canInteract(any<Role>()) } returns true
        every {
            guild.modifyMemberRoles(
                member,
                any<Collection<Role>>(),
                any<Collection<Role>>(),
            )
        } returns roleAction
        every { roleAction.reason(any()) } returns roleAction
        every { roleAction.submit() } returns completedRoleAction
        session.activate(jda, channels())

        val service =
            DiscordRoleService(
                session,
                config,
                DiscordRoleFactsProvider { _, _ ->
                    CompletableFuture.completedFuture(DiscordRoleFacts(setOf("admin"), emptySet()))
                },
            )
        val result = service.reconcile(link).join()

        result.status shouldBe DiscordRoleReconcileResult.Status.UPDATED
        result.addedRoleIds shouldContainExactlyInAnyOrder
            setOf(config.verifiedRoleId, config.playerRoleId, "1079927120614146138")
        result.nicknameChanged shouldBe false
        result.nicknameSkipped shouldBe true
        result.reason shouldBe "nickname-hierarchy"
        val addedRoles = slot<Collection<Role>>()
        verify(exactly = 1) {
            guild.modifyMemberRoles(
                member,
                capture(addedRoles),
                emptyList(),
            )
        }
        addedRoles.captured.map(Role::getId).toSet() shouldBe result.addedRoleIds
        verify(exactly = 0) { member.modifyNickname(any()) }
    }

    "unlink can remove manageable roles from an unmodifiable member when no managed nickname remains" {
        val root = Files.createTempDirectory("discord-owner-role-clear")
        val config = verificationConfig(root)
        val session = DiscordSession()
        val jda = mockk<JDA>()
        val guild = mockk<Guild>()
        val selfMember = mockk<SelfMember>()
        val member = mockk<Member>()
        val memberAction = mockk<CacheRestAction<Member>>()
        val roleAction = mockk<AuditableRestAction<Void>>()
        val roles = config.managedRoleIds.associateWith(::role)
        val assigned = listOf(roles.getValue(config.verifiedRoleId), roles.getValue(config.playerRoleId))
        val link = DiscordIdentityLink(UUID.randomUUID(), "GrocerMC", "123456789012345678", 1, 1)
        val completedRoleAction = CompletableFuture<Void>().also { it.complete(null) }

        every { jda.getGuildById(config.guildId) } returns guild
        every { guild.selfMember } returns selfMember
        every { guild.getRoleById(any<String>()) } answers { roles[firstArg()] }
        every { guild.retrieveMemberById(link.discordUserId) } returns memberAction
        every { memberAction.submit() } returns CompletableFuture.completedFuture(member)
        every { member.roles } returns assigned
        every { member.nickname } returns null
        every { selfMember.canInteract(member) } returns false
        every { selfMember.canInteract(any<Role>()) } returns true
        every {
            guild.modifyMemberRoles(
                member,
                any<Collection<Role>>(),
                any<Collection<Role>>(),
            )
        } returns roleAction
        every { roleAction.reason(any()) } returns roleAction
        every { roleAction.submit() } returns completedRoleAction
        session.activate(jda, channels())

        val service =
            DiscordRoleService(
                session,
                config,
                DiscordRoleFactsProvider { _, _ -> CompletableFuture.completedFuture(DiscordRoleFacts(emptySet(), emptySet())) },
            )
        val result = service.clearManagedRoles(link).join()

        result.status shouldBe DiscordRoleReconcileResult.Status.UPDATED
        result.removedRoleIds shouldContainExactlyInAnyOrder setOf(config.verifiedRoleId, config.playerRoleId)
        val removedRoles = slot<Collection<Role>>()
        verify(exactly = 1) {
            guild.modifyMemberRoles(
                member,
                emptyList(),
                capture(removedRoles),
            )
        }
        removedRoles.captured.map(Role::getId).toSet() shouldBe result.removedRoleIds
    }
})

private fun role(id: String): Role {
    val role = mockk<Role>()
    every { role.id } returns id
    every { role.isManaged } returns false
    every { role.isPublicRole } returns false
    return role
}

private fun channels(): DiscordChannels {
    val channel = mockk<TextChannel>()
    return DiscordChannels(null, null, null, channel, channel, null)
}
