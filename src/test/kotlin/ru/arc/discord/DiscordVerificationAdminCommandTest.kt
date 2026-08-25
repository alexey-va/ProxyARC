package ru.arc.discord

import com.velocitypowered.api.command.CommandSource
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DiscordVerificationAdminCommandTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()

    "permission denial happens before gateway lookup or mutation" {
        val sent = mutableListOf<Component>()
        val source = commandSource(permitted = false, sent)
        var gatewayRequests = 0
        val command =
            DiscordVerificationAdminCommand(
                gatewayProvider = {
                    gatewayRequests++
                    FakeAdminGateway()
                },
                messagesProvider = { verificationConfig(Files.createTempDirectory("discord-admin-denied")).messages },
            )

        command.execute(source, listOf("unlink", "PlayerOne", "confirm"))

        gatewayRequests shouldBe 0
        sent.size shouldBe 1
    }

    "unlink requires the explicit confirmation token" {
        val sent = mutableListOf<Component>()
        val source = commandSource(permitted = true, sent)
        val gateway = FakeAdminGateway()
        val messages = verificationConfig(Files.createTempDirectory("discord-admin-confirm")).messages
        val command = DiscordVerificationAdminCommand({ gateway }, { messages })

        command.execute(source, listOf("unlink", "PlayerOne"))

        gateway.unlinkCalls shouldBe 0
        plain.serialize(sent.single()).contains("confirm") shouldBe true
    }

    "status reports the persisted identity and last reconciliation diagnostic" {
        val sent = mutableListOf<Component>()
        val source = commandSource(permitted = true, sent)
        val gateway = FakeAdminGateway()
        val messages = verificationConfig(Files.createTempDirectory("discord-admin-status")).messages
        val command = DiscordVerificationAdminCommand({ gateway }, { messages })

        command.execute(source, listOf("status", gateway.link.playerName))

        val rendered = plain.serialize(sent.single())
        rendered.contains(gateway.link.playerName) shouldBe true
        rendered.contains(gateway.link.playerUuid.toString()) shouldBe true
        rendered.contains(gateway.link.discordUserId) shouldBe true
        rendered.startsWith("\n  ") shouldBe true
        rendered.endsWith("\n") shouldBe true
    }

    "manual sync uses the admin trigger and unlink targets the looked-up link" {
        val sent = mutableListOf<Component>()
        val source = commandSource(permitted = true, sent)
        val gateway = FakeAdminGateway()
        val messages = verificationConfig(Files.createTempDirectory("discord-admin-actions")).messages
        val command = DiscordVerificationAdminCommand({ gateway }, { messages })

        command.execute(source, listOf("sync", gateway.link.discordUserId))
        command.execute(source, listOf("unlink", gateway.link.playerUuid.toString(), "confirm"))

        gateway.syncCalls shouldBe 1
        gateway.lastTrigger shouldBe DiscordRoleSyncTrigger.ADMIN
        gateway.unlinkCalls shouldBe 1
        gateway.lastUnlink shouldBe gateway.link
        sent.size shouldBe 2
    }
})

private fun commandSource(
    permitted: Boolean,
    sent: MutableList<Component>,
): CommandSource =
    mockk(relaxed = true) {
        every { hasPermission(DiscordVerificationAdminCommand.PERMISSION) } returns permitted
        every { sendMessage(any<Component>()) } answers { sent += firstArg<Component>() }
    }

private class FakeAdminGateway : DiscordVerificationAdminGateway {
    val link =
        DiscordIdentityLink(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "PlayerOne",
            "123456789012345678",
            1L,
            1L,
        )
    var syncCalls = 0
    var unlinkCalls = 0
    var lastTrigger: DiscordRoleSyncTrigger? = null
    var lastUnlink: DiscordIdentityLink? = null

    override fun lookupIdentity(query: String): DiscordIdentityLookupResult =
        DiscordIdentityLookupResult.Linked(
            link,
            DiscordRoleSyncDiagnostic(
                attemptedAt = System.currentTimeMillis(),
                trigger = DiscordRoleSyncTrigger.PERIODIC,
                result = DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.UPDATED),
            ),
        )

    override fun reconcileIdentity(
        link: DiscordIdentityLink,
        trigger: DiscordRoleSyncTrigger,
    ): CompletableFuture<DiscordRoleReconcileResult> {
        syncCalls++
        lastTrigger = trigger
        return CompletableFuture.completedFuture(
            DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.UPDATED),
        )
    }

    override fun unlinkIdentity(link: DiscordIdentityLink): CompletableFuture<DiscordVerificationWorkflowResult> {
        unlinkCalls++
        lastUnlink = link
        return CompletableFuture.completedFuture(DiscordVerificationWorkflowResult.Unlinked(link))
    }
}
