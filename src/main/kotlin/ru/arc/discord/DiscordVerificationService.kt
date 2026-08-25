package ru.arc.discord

import java.util.UUID
import java.util.concurrent.CompletableFuture

internal sealed interface DiscordVerificationWorkflowResult {
    data class Verified(
        val link: DiscordIdentityLink,
        val idempotent: Boolean,
        val reconciliation: DiscordRoleReconcileResult,
    ) : DiscordVerificationWorkflowResult

    data class Recovered(
        val link: DiscordIdentityLink,
        val reconciliation: DiscordRoleReconcileResult,
        val previousLink: DiscordIdentityLink,
    ) : DiscordVerificationWorkflowResult

    data class Unlinked(val previousLink: DiscordIdentityLink) : DiscordVerificationWorkflowResult

    data class RateLimited(val retryAt: Long) : DiscordVerificationWorkflowResult

    data object InvalidOrExpired : DiscordVerificationWorkflowResult

    data object MinecraftAlreadyLinked : DiscordVerificationWorkflowResult

    data object DiscordAlreadyLinked : DiscordVerificationWorkflowResult

    data object NotLinked : DiscordVerificationWorkflowResult

    data object RecoveryCancelled : DiscordVerificationWorkflowResult

    data class RoleFailure(val result: DiscordRoleReconcileResult) : DiscordVerificationWorkflowResult

    data object Conflict : DiscordVerificationWorkflowResult

    data object Unavailable : DiscordVerificationWorkflowResult
}

internal class DiscordVerificationService(
    private val identities: DiscordIdentityService,
    private val roles: DiscordRoleReconciler,
    private val telemetry: DiscordVerificationTelemetry = DiscordVerificationTelemetry(),
    private val workflowObserver: (DiscordVerificationWorkflowResult) -> Unit = {},
    private val recoveryGuard: (DiscordChallengeCompletionResult.RecoveryPrepared) -> CompletableFuture<Boolean> = {
        CompletableFuture.completedFuture(true)
    },
) {
    fun isAvailable(): Boolean = identities.isAvailable()

    fun issueLinkChallenge(
        playerUuid: UUID,
        playerName: String,
    ): DiscordChallengeIssueResult =
        identities.issueLinkChallenge(playerUuid, playerName).also { result ->
            telemetry.recordVerification(
                DiscordVerificationOperation.LINK_CHALLENGE,
                result.verificationOutcome(),
            )
        }

    fun issueRecoveryChallenge(
        playerUuid: UUID,
        playerName: String,
    ): DiscordChallengeIssueResult =
        identities.issueRecoveryChallenge(playerUuid, playerName).also { result ->
            telemetry.recordVerification(
                DiscordVerificationOperation.RECOVERY_CHALLENGE,
                result.verificationOutcome(),
            )
        }

    fun completeFromDiscord(
        code: String,
        discordUserId: String,
    ): CompletableFuture<DiscordVerificationWorkflowResult> {
        val future: CompletableFuture<DiscordVerificationWorkflowResult> =
            when (val completion = identities.completeChallenge(code, discordUserId)) {
            is DiscordChallengeCompletionResult.Linked -> reconcileVerified(completion)
            is DiscordChallengeCompletionResult.RecoveryPrepared -> completeRecovery(completion)
            DiscordChallengeCompletionResult.InvalidOrExpired -> completed(DiscordVerificationWorkflowResult.InvalidOrExpired)
            DiscordChallengeCompletionResult.MinecraftAlreadyLinked -> completed(DiscordVerificationWorkflowResult.MinecraftAlreadyLinked)
            DiscordChallengeCompletionResult.DiscordAlreadyLinked -> completed(DiscordVerificationWorkflowResult.DiscordAlreadyLinked)
            is DiscordChallengeCompletionResult.RateLimited ->
                completed(DiscordVerificationWorkflowResult.RateLimited(completion.retryAt))
            DiscordChallengeCompletionResult.Unavailable -> completed(DiscordVerificationWorkflowResult.Unavailable)
        }
        return trackWorkflow(DiscordVerificationOperation.DISCORD_COMPLETE, future)
    }

    fun unlinkByMinecraft(playerUuid: UUID): CompletableFuture<DiscordVerificationWorkflowResult> {
        val link = identities.findByPlayerUuid(playerUuid)
            ?: return trackWorkflow(
                DiscordVerificationOperation.UNLINK,
                completed(
                    if (identities.isAvailable()) {
                        DiscordVerificationWorkflowResult.NotLinked
                    } else {
                        DiscordVerificationWorkflowResult.Unavailable
                    },
                ),
            )
        return unlinkExpected(link)
    }

    fun unlinkByDiscord(discordUserId: String): CompletableFuture<DiscordVerificationWorkflowResult> {
        val link = identities.findByDiscordUserId(discordUserId)
            ?: return trackWorkflow(
                DiscordVerificationOperation.UNLINK,
                completed(
                    if (identities.isAvailable()) {
                        DiscordVerificationWorkflowResult.NotLinked
                    } else {
                        DiscordVerificationWorkflowResult.Unavailable
                    },
                ),
            )
        return unlinkExpected(link)
    }

    fun unlinkExpected(expected: DiscordIdentityLink): CompletableFuture<DiscordVerificationWorkflowResult> {
        val current = identities.findByPlayerUuid(expected.playerUuid)
        val future: CompletableFuture<DiscordVerificationWorkflowResult> =
            when {
                current == null -> completed(DiscordVerificationWorkflowResult.NotLinked)
                current.discordUserId != expected.discordUserId -> completed(DiscordVerificationWorkflowResult.Conflict)
                else -> clearThenUnlink(current)
            }
        return trackWorkflow(DiscordVerificationOperation.UNLINK, future)
    }

    fun reconcilePlayer(
        playerUuid: UUID,
        playerName: String,
        trigger: DiscordRoleSyncTrigger = DiscordRoleSyncTrigger.SERVER_CONNECT,
    ): CompletableFuture<DiscordRoleReconcileResult> {
        val link = identities.updatePlayerName(playerUuid, playerName)
            ?: return CompletableFuture.completedFuture(
                DiscordRoleReconcileResult(
                    if (identities.isAvailable()) {
                        DiscordRoleReconcileResult.Status.UNCHANGED
                    } else {
                        DiscordRoleReconcileResult.Status.PROVIDER_UNAVAILABLE
                    },
                ),
            )
        return roles.reconcile(link).whenComplete { result, _ ->
            if (result != null) recordRoleSync(link, trigger, result)
        }
    }

    fun reconcileAll(
        trigger: DiscordRoleSyncTrigger = DiscordRoleSyncTrigger.PERIODIC,
    ): CompletableFuture<List<DiscordRoleReconcileResult>> {
        val links = identities.allLinks()
        val futures = links.map { link ->
            roles.reconcile(link).whenComplete { result, _ ->
                if (result != null) recordRoleSync(link, trigger, result)
            }
        }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
            futures.map { it.getNow(DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.FAILED)) }
        }
    }

    fun findByPlayerUuid(playerUuid: UUID): DiscordIdentityLink? = identities.findByPlayerUuid(playerUuid)

    fun findByDiscordUserId(discordUserId: String): DiscordIdentityLink? = identities.findByDiscordUserId(discordUserId)

    fun lookup(query: String): DiscordIdentityLookupResult {
        if (!identities.isAvailable()) return DiscordIdentityLookupResult.Unavailable
        val normalized = query.trim()
        if (normalized.length !in 1..MAX_LOOKUP_LENGTH) return DiscordIdentityLookupResult.Invalid
        val uuid = runCatching { UUID.fromString(normalized) }.getOrNull()
        if (uuid != null) {
            val byUuid = identities.findByPlayerUuid(uuid) ?: return DiscordIdentityLookupResult.NotLinked
            return DiscordIdentityLookupResult.Linked(byUuid, telemetry.diagnostic(byUuid.playerUuid))
        }
        if (DiscordVerificationConfig.validSnowflake(normalized)) {
            val byDiscord = identities.findByDiscordUserId(normalized) ?: return DiscordIdentityLookupResult.NotLinked
            return DiscordIdentityLookupResult.Linked(byDiscord, telemetry.diagnostic(byDiscord.playerUuid))
        }
        if (!PLAYER_NAME.matches(normalized)) return DiscordIdentityLookupResult.Invalid
        val byName = identities.findAllByPlayerName(normalized)
        if (byName.isEmpty()) return DiscordIdentityLookupResult.NotLinked
        if (byName.size > 1) return DiscordIdentityLookupResult.Ambiguous
        return DiscordIdentityLookupResult.Linked(byName.single(), telemetry.diagnostic(byName.single().playerUuid))
    }

    fun metricsSnapshot(): List<ru.arc.metrics.core.MetricPoint> = telemetry.snapshot(identities.stats())

    private fun reconcileVerified(
        completion: DiscordChallengeCompletionResult.Linked,
    ): CompletableFuture<DiscordVerificationWorkflowResult> =
        roles.reconcile(completion.link).thenApply { result ->
            recordRoleSync(completion.link, DiscordRoleSyncTrigger.VERIFY, result)
            DiscordVerificationWorkflowResult.Verified(completion.link, completion.idempotent, result)
        }

    private fun completeRecovery(
        prepared: DiscordChallengeCompletionResult.RecoveryPrepared,
    ): CompletableFuture<DiscordVerificationWorkflowResult> =
        recoveryGuard(prepared).thenCompose { allowed ->
            if (!allowed) {
                identities.releaseRecoveryClaim(
                    prepared.challengeId,
                    prepared.newDiscordUserId,
                    "cancelled-by-previous-discord",
                )
                return@thenCompose completed(DiscordVerificationWorkflowResult.RecoveryCancelled)
            }
            roles.clearManagedRoles(prepared.currentLink).thenCompose { clearResult ->
                recordRoleSync(prepared.currentLink, DiscordRoleSyncTrigger.RECOVERY, clearResult)
                if (!clearResult.successful) {
                    identities.releaseRecoveryClaim(
                        prepared.challengeId,
                        prepared.newDiscordUserId,
                        clearResult.status.name,
                    )
                    return@thenCompose completed(DiscordVerificationWorkflowResult.RoleFailure(clearResult))
                }
                when (val recovered = identities.completeRecovery(prepared.challengeId, prepared.newDiscordUserId)) {
                    is DiscordRecoveryCompletionResult.Recovered ->
                        roles.reconcile(recovered.newLink).thenApply { reconcileResult ->
                            recordRoleSync(recovered.newLink, DiscordRoleSyncTrigger.RECOVERY, reconcileResult)
                            DiscordVerificationWorkflowResult.Recovered(
                                recovered.newLink,
                                reconcileResult,
                                recovered.previousLink,
                            )
                        }
                    DiscordRecoveryCompletionResult.Conflict ->
                        restoreRolesAfterIdentityFailure(
                            prepared.currentLink,
                            DiscordRoleSyncTrigger.RECOVERY,
                            DiscordVerificationWorkflowResult.Conflict,
                        )
                    DiscordRecoveryCompletionResult.Unavailable ->
                        restoreRolesAfterIdentityFailure(
                            prepared.currentLink,
                            DiscordRoleSyncTrigger.RECOVERY,
                            DiscordVerificationWorkflowResult.Unavailable,
                        )
                }
            }
        }

    private fun clearThenUnlink(link: DiscordIdentityLink): CompletableFuture<DiscordVerificationWorkflowResult> =
        roles.clearManagedRoles(link).thenCompose { clearResult ->
            recordRoleSync(link, DiscordRoleSyncTrigger.UNLINK, clearResult)
            if (!clearResult.successful) {
                return@thenCompose completed(DiscordVerificationWorkflowResult.RoleFailure(clearResult))
            }
            when (val result = identities.completeUnlink(link.playerUuid, link.discordUserId)) {
                is DiscordUnlinkResult.Unlinked -> {
                    telemetry.removeIdentity(link.playerUuid)
                    completed(DiscordVerificationWorkflowResult.Unlinked(result.previousLink))
                }
                DiscordUnlinkResult.NotLinked -> completed(DiscordVerificationWorkflowResult.NotLinked)
                DiscordUnlinkResult.Conflict ->
                    restoreRolesAfterIdentityFailure(
                        link,
                        DiscordRoleSyncTrigger.UNLINK,
                        DiscordVerificationWorkflowResult.Conflict,
                    )
                DiscordUnlinkResult.Unavailable ->
                    restoreRolesAfterIdentityFailure(
                        link,
                        DiscordRoleSyncTrigger.UNLINK,
                        DiscordVerificationWorkflowResult.Unavailable,
                    )
            }
        }

    private fun restoreRolesAfterIdentityFailure(
        link: DiscordIdentityLink,
        trigger: DiscordRoleSyncTrigger,
        outcome: DiscordVerificationWorkflowResult,
    ): CompletableFuture<DiscordVerificationWorkflowResult> {
        val current = identities.findByPlayerUuid(link.playerUuid)
        if (current?.discordUserId != link.discordUserId) return completed(outcome)
        return roles.reconcile(current).handle { result, _ ->
            if (result != null) recordRoleSync(current, trigger, result)
            outcome
        }
    }

    private fun recordRoleSync(
        link: DiscordIdentityLink,
        trigger: DiscordRoleSyncTrigger,
        result: DiscordRoleReconcileResult,
    ) {
        identities.recordReconciliation(link, result)
        telemetry.recordRoleSync(link, trigger, result)
    }

    private fun trackWorkflow(
        operation: DiscordVerificationOperation,
        future: CompletableFuture<DiscordVerificationWorkflowResult>,
    ): CompletableFuture<DiscordVerificationWorkflowResult> =
        future.whenComplete { result, error ->
            telemetry.recordVerification(
                operation,
                if (error != null || result == null) DiscordVerificationOutcome.FAILED else result.verificationOutcome(),
            )
            if (error == null && result != null) runCatching { workflowObserver(result) }
        }

    private fun <T> completed(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)

    companion object {
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        private const val MAX_LOOKUP_LENGTH = 64
    }
}

private fun DiscordChallengeIssueResult.verificationOutcome(): DiscordVerificationOutcome =
    when (this) {
        is DiscordChallengeIssueResult.Issued -> DiscordVerificationOutcome.SUCCESS
        is DiscordChallengeIssueResult.AlreadyLinked -> DiscordVerificationOutcome.ALREADY_LINKED
        DiscordChallengeIssueResult.NotLinked -> DiscordVerificationOutcome.NOT_LINKED
        is DiscordChallengeIssueResult.RateLimited -> DiscordVerificationOutcome.RATE_LIMITED
        DiscordChallengeIssueResult.Unavailable -> DiscordVerificationOutcome.UNAVAILABLE
    }

private fun DiscordVerificationWorkflowResult.verificationOutcome(): DiscordVerificationOutcome =
    when (this) {
        is DiscordVerificationWorkflowResult.Verified ->
            when {
                idempotent -> DiscordVerificationOutcome.IDEMPOTENT
                !reconciliation.successful || reconciliation.nicknameSkipped -> DiscordVerificationOutcome.PARTIAL
                else -> DiscordVerificationOutcome.SUCCESS
            }
        is DiscordVerificationWorkflowResult.Recovered ->
            if (!reconciliation.successful || reconciliation.nicknameSkipped) {
                DiscordVerificationOutcome.PARTIAL
            } else {
                DiscordVerificationOutcome.SUCCESS
            }
        is DiscordVerificationWorkflowResult.Unlinked -> DiscordVerificationOutcome.SUCCESS
        is DiscordVerificationWorkflowResult.RateLimited -> DiscordVerificationOutcome.RATE_LIMITED
        DiscordVerificationWorkflowResult.InvalidOrExpired -> DiscordVerificationOutcome.INVALID
        DiscordVerificationWorkflowResult.MinecraftAlreadyLinked,
        DiscordVerificationWorkflowResult.DiscordAlreadyLinked,
        -> DiscordVerificationOutcome.ALREADY_LINKED
        DiscordVerificationWorkflowResult.NotLinked -> DiscordVerificationOutcome.NOT_LINKED
        DiscordVerificationWorkflowResult.RecoveryCancelled -> DiscordVerificationOutcome.CONFLICT
        is DiscordVerificationWorkflowResult.RoleFailure -> DiscordVerificationOutcome.ROLE_FAILURE
        DiscordVerificationWorkflowResult.Conflict -> DiscordVerificationOutcome.CONFLICT
        DiscordVerificationWorkflowResult.Unavailable -> DiscordVerificationOutcome.UNAVAILABLE
    }
