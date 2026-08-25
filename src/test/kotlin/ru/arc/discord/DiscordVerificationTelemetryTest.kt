package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class DiscordVerificationTelemetryTest : FreeSpec({
    "exports bounded aggregate metrics without identity labels" {
        var now = 2_000_000L
        val telemetry = DiscordVerificationTelemetry { now }
        val link =
            DiscordIdentityLink(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "PlayerOne",
                "123456789012345678",
                1L,
                1L,
            )
        telemetry.recordVerification(DiscordVerificationOperation.DISCORD_COMPLETE, DiscordVerificationOutcome.SUCCESS)
        telemetry.recordRoleSync(
            link,
            DiscordRoleSyncTrigger.LUCKPERMS_EVENT,
            DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.UPDATED),
        )
        now += 5_000L

        val points = telemetry.snapshot(DiscordIdentityStats(true, 3, 2))

        points.single { it.name == "arc_discord_identity_links" }.value shouldBe 3.0
        points.single { it.name == "arc_discord_verification_pending_challenges" }.value shouldBe 2.0
        points.single { it.name == "arc_discord_role_sync_lag_seconds" }.value shouldBe 5.0
        points.single {
            it.name == "arc_discord_role_sync_results" && it.tags["status"] == "updated"
        }.value shouldBe 1.0
        points.single {
            it.name == "arc_discord_verification_results" &&
                it.tags == mapOf("operation" to "discord_complete", "outcome" to "success")
        }.value shouldBe 1.0
        points.flatMap { it.tags.keys }.distinct() shouldContainExactly listOf("status", "operation", "outcome")
        points.flatMap { it.tags.values }.none { it.contains("PlayerOne") || it.contains(link.playerUuid.toString()) } shouldBe true
    }

    "stores the latest sync trigger and a sanitized bounded reason" {
        val telemetry = DiscordVerificationTelemetry { 9_000L }
        val link = DiscordIdentityLink(UUID.randomUUID(), "PlayerOne", "123456789012345678", 1L, 1L)
        val unsafeReason = "bad\u0000reason" + "x".repeat(200)

        telemetry.recordRoleSync(
            link,
            DiscordRoleSyncTrigger.ADMIN,
            DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.FAILED, reason = unsafeReason),
        )

        val diagnostic = telemetry.diagnostic(link.playerUuid)!!
        diagnostic.trigger shouldBe DiscordRoleSyncTrigger.ADMIN
        diagnostic.result.status shouldBe DiscordRoleReconcileResult.Status.FAILED
        diagnostic.result.reason?.length shouldBe 96
        diagnostic.result.reason?.contains('\u0000') shouldBe false
        telemetry.removeIdentity(link.playerUuid)
        telemetry.diagnostic(link.playerUuid) shouldBe null
    }
})
