package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DiscordVerificationServiceTest : FreeSpec({
    val player = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val oldDiscord = "1073279640912789595"
    val newDiscord = "1083092420394221699"

    fun fixture(): WorkflowFixture {
        val root = Files.createTempDirectory("discord-verification-workflow")
        var now = 1_000_000L
        val codes = ArrayDeque(listOf("ABCDEFGH", "JKLMNPQR"))
        val identities =
            DiscordIdentityService(
                DiscordIdentityStore(root.resolve("data/discord-identities.json")),
                verificationConfig(root),
                clock = { now },
                codeGenerator = { codes.removeFirst() },
            )
        val roles = RecordingRoleReconciler()
        return WorkflowFixture(
            identities,
            roles,
            DiscordVerificationService(identities, roles),
            setNow = { now = it },
        )
    }

    "normal verification commits the identity before role reconciliation" {
        val fixture = fixture()
        fixture.roles.onReconcile = { link ->
            fixture.identities.findByPlayerUuid(link.playerUuid)?.discordUserId shouldBe oldDiscord
        }
        val code =
            (fixture.service.issueLinkChallenge(player, "PlayerOne") as DiscordChallengeIssueResult.Issued).code

        val result = fixture.service.completeFromDiscord(code, oldDiscord).join()

        (result as DiscordVerificationWorkflowResult.Verified).reconciliation.successful shouldBe true
        fixture.roles.events shouldBe listOf("reconcile:$oldDiscord")
    }

    "unlink preserves the link when managed roles cannot be cleared" {
        val fixture = fixture()
        val code =
            (fixture.service.issueLinkChallenge(player, "PlayerOne") as DiscordChallengeIssueResult.Issued).code
        fixture.service.completeFromDiscord(code, oldDiscord).join()
        fixture.roles.events.clear()
        fixture.roles.clearResult =
            DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.HIERARCHY_BLOCKED)

        fixture.service.unlinkByMinecraft(player).join() as DiscordVerificationWorkflowResult.RoleFailure

        fixture.identities.findByPlayerUuid(player)?.discordUserId shouldBe oldDiscord
        fixture.roles.events shouldBe listOf("clear:$oldDiscord")
    }

    "unlink deletes the link only after managed roles are cleared" {
        val fixture = fixture()
        val code =
            (fixture.service.issueLinkChallenge(player, "PlayerOne") as DiscordChallengeIssueResult.Issued).code
        fixture.service.completeFromDiscord(code, oldDiscord).join()
        fixture.roles.events.clear()
        fixture.roles.onClear = {
            fixture.identities.findByPlayerUuid(player)?.discordUserId shouldBe oldDiscord
        }

        fixture.service.unlinkByMinecraft(player).join() as DiscordVerificationWorkflowResult.Unlinked

        fixture.identities.findByPlayerUuid(player) shouldBe null
        fixture.roles.events shouldBe listOf("clear:$oldDiscord")
    }

    "recovery clears the previous Discord member before replacing the snowflake" {
        val fixture = fixture()
        val linkCode =
            (fixture.service.issueLinkChallenge(player, "PlayerOne") as DiscordChallengeIssueResult.Issued).code
        fixture.service.completeFromDiscord(linkCode, oldDiscord).join()
        fixture.roles.events.clear()
        fixture.setNow(1_061_000L)
        val recoveryCode =
            (fixture.service.issueRecoveryChallenge(player, "PlayerOne") as DiscordChallengeIssueResult.Issued).code
        fixture.roles.onClear = { link ->
            link.discordUserId shouldBe oldDiscord
            fixture.identities.findByPlayerUuid(player)?.discordUserId shouldBe oldDiscord
        }

        val result = fixture.service.completeFromDiscord(recoveryCode, newDiscord).join()

        (result as DiscordVerificationWorkflowResult.Recovered).link.discordUserId shouldBe newDiscord
        fixture.roles.events shouldBe listOf("clear:$oldDiscord", "reconcile:$newDiscord")
    }
})

private data class WorkflowFixture(
    val identities: DiscordIdentityService,
    val roles: RecordingRoleReconciler,
    val service: DiscordVerificationService,
    val setNow: (Long) -> Unit,
)

private class RecordingRoleReconciler : DiscordRoleReconciler {
    val events = mutableListOf<String>()
    var clearResult = DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.UPDATED)
    var onClear: (DiscordIdentityLink) -> Unit = {}
    var onReconcile: (DiscordIdentityLink) -> Unit = {}

    override fun reconcile(link: DiscordIdentityLink): CompletableFuture<DiscordRoleReconcileResult> {
        events += "reconcile:${link.discordUserId}"
        onReconcile(link)
        return CompletableFuture.completedFuture(
            DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.UPDATED),
        )
    }

    override fun clearManagedRoles(link: DiscordIdentityLink): CompletableFuture<DiscordRoleReconcileResult> {
        events += "clear:${link.discordUserId}"
        onClear(link)
        return CompletableFuture.completedFuture(clearResult)
    }
}
