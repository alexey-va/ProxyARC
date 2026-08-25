package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.TestTaskScheduler
import java.nio.file.Files
import java.util.UUID

class DiscordRoleSyncCoordinatorTest : FreeSpec({
    "runs initial and periodic reconciliation and closes owned resources" {
        val fixture = coordinatorFixture()

        fixture.coordinator.start()
        fixture.roles.events shouldBe listOf("reconcile:${fixture.discordId}")
        fixture.scheduler.timerCount() shouldBe 1
        fixture.scheduler.advanceMs(300_000)
        fixture.roles.events shouldBe
            listOf("reconcile:${fixture.discordId}", "reconcile:${fixture.discordId}")

        fixture.coordinator.close()
        fixture.subscriptionClosed() shouldBe true
        fixture.scheduler.timerCount() shouldBe 0
    }

    "debounces LuckPerms recalculations and suppresses short event loops" {
        val fixture = coordinatorFixture()
        fixture.coordinator.start()
        fixture.roles.events.clear()
        fixture.now(60_000L)

        fixture.emit(fixture.playerUuid)
        fixture.emit(fixture.playerUuid)
        fixture.scheduler.advanceMs(999L)
        fixture.roles.events shouldBe emptyList()
        fixture.scheduler.advanceMs(1L)
        fixture.roles.events shouldBe listOf("reconcile:${fixture.discordId}")

        fixture.now(62_000L)
        fixture.emit(fixture.playerUuid)
        fixture.scheduler.advanceMs(1_000L)
        fixture.roles.events shouldBe listOf("reconcile:${fixture.discordId}")

        fixture.now(66_000L)
        fixture.emit(fixture.playerUuid)
        fixture.scheduler.advanceMs(1_000L)
        fixture.roles.events shouldBe
            listOf("reconcile:${fixture.discordId}", "reconcile:${fixture.discordId}")
    }

    "ignores LuckPerms events for identities that are not linked" {
        val fixture = coordinatorFixture()
        fixture.coordinator.start()
        fixture.roles.events.clear()
        fixture.now(60_000L)

        fixture.emit(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        fixture.scheduler.advanceMs(1_000L)

        fixture.roles.events shouldBe emptyList()
    }

    "close prevents a queued event callback from reconciling" {
        val fixture = coordinatorFixture()
        fixture.coordinator.start()
        fixture.roles.events.clear()
        fixture.now(60_000L)
        fixture.emit(fixture.playerUuid)

        fixture.coordinator.close()
        fixture.scheduler.advanceMs(1_000L)

        fixture.roles.events shouldBe emptyList()
    }
})

private data class CoordinatorFixture(
    val playerUuid: UUID,
    val discordId: String,
    val roles: RecordingCoordinatorRoleReconciler,
    val scheduler: TestTaskScheduler,
    val coordinator: DiscordRoleSyncCoordinator,
    val emit: (UUID) -> Unit,
    val now: (Long) -> Unit,
    val subscriptionClosed: () -> Boolean,
)

private fun coordinatorFixture(): CoordinatorFixture {
    val root = Files.createTempDirectory("discord-role-coordinator")
    val config = verificationConfig(root)
    var now = 1_000L
    val playerUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val discordId = "123456789012345678"
    val identities =
        DiscordIdentityService(
            DiscordIdentityStore(root.resolve("data/discord-identities.json")),
            config,
            clock = { now },
            codeGenerator = { "ABCDEFGH" },
        )
    val roles = RecordingCoordinatorRoleReconciler()
    val service = DiscordVerificationService(identities, roles)
    val code = (service.issueLinkChallenge(playerUuid, "PlayerOne") as DiscordChallengeIssueResult.Issued).code
    service.completeFromDiscord(code, discordId).join()
    roles.events.clear()
    val scheduler = TestTaskScheduler()
    var listener: ((UUID) -> Unit)? = null
    var closed = false
    val coordinator =
        DiscordRoleSyncCoordinator(
            config = config,
            verification = service,
            scheduler = scheduler,
            eventSubscriber =
                DiscordLuckPermsEventSubscriber { installed ->
                    listener = installed
                    AutoCloseable { closed = true }
                },
            clock = { now },
        )
    return CoordinatorFixture(
        playerUuid = playerUuid,
        discordId = discordId,
        roles = roles,
        scheduler = scheduler,
        coordinator = coordinator,
        emit = { uuid -> requireNotNull(listener)(uuid) },
        now = { now = it },
        subscriptionClosed = { closed },
    )
}

private class RecordingCoordinatorRoleReconciler : DiscordRoleReconciler {
    val events = mutableListOf<String>()

    override fun reconcile(link: DiscordIdentityLink) =
        java.util.concurrent.CompletableFuture.completedFuture(
            DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.UPDATED),
        ).also { events += "reconcile:${link.discordUserId}" }

    override fun clearManagedRoles(link: DiscordIdentityLink) =
        java.util.concurrent.CompletableFuture.completedFuture(
            DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.UPDATED),
        )
}
