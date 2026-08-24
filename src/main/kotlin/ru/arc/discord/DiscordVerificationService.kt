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
    ) : DiscordVerificationWorkflowResult

    data class Unlinked(val previousLink: DiscordIdentityLink) : DiscordVerificationWorkflowResult

    data class RateLimited(val retryAt: Long) : DiscordVerificationWorkflowResult

    data object InvalidOrExpired : DiscordVerificationWorkflowResult

    data object MinecraftAlreadyLinked : DiscordVerificationWorkflowResult

    data object DiscordAlreadyLinked : DiscordVerificationWorkflowResult

    data object NotLinked : DiscordVerificationWorkflowResult

    data class RoleFailure(val result: DiscordRoleReconcileResult) : DiscordVerificationWorkflowResult

    data object Conflict : DiscordVerificationWorkflowResult

    data object Unavailable : DiscordVerificationWorkflowResult
}

internal class DiscordVerificationService(
    private val identities: DiscordIdentityService,
    private val roles: DiscordRoleReconciler,
) {
    fun isAvailable(): Boolean = identities.isAvailable()

    fun issueLinkChallenge(
        playerUuid: UUID,
        playerName: String,
    ): DiscordChallengeIssueResult = identities.issueLinkChallenge(playerUuid, playerName)

    fun issueRecoveryChallenge(
        playerUuid: UUID,
        playerName: String,
    ): DiscordChallengeIssueResult = identities.issueRecoveryChallenge(playerUuid, playerName)

    fun completeFromDiscord(
        code: String,
        discordUserId: String,
    ): CompletableFuture<DiscordVerificationWorkflowResult> =
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

    fun unlinkByMinecraft(playerUuid: UUID): CompletableFuture<DiscordVerificationWorkflowResult> {
        val link = identities.findByPlayerUuid(playerUuid)
            ?: return completed(
                if (identities.isAvailable()) {
                    DiscordVerificationWorkflowResult.NotLinked
                } else {
                    DiscordVerificationWorkflowResult.Unavailable
                },
            )
        return clearThenUnlink(link)
    }

    fun unlinkByDiscord(discordUserId: String): CompletableFuture<DiscordVerificationWorkflowResult> {
        val link = identities.findByDiscordUserId(discordUserId)
            ?: return completed(
                if (identities.isAvailable()) {
                    DiscordVerificationWorkflowResult.NotLinked
                } else {
                    DiscordVerificationWorkflowResult.Unavailable
                },
            )
        return clearThenUnlink(link)
    }

    fun reconcilePlayer(
        playerUuid: UUID,
        playerName: String,
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
            if (result != null) identities.recordReconciliation(link, result)
        }
    }

    fun reconcileAll(): CompletableFuture<List<DiscordRoleReconcileResult>> {
        val links = identities.allLinks()
        val futures = links.map { link ->
            roles.reconcile(link).whenComplete { result, _ ->
                if (result != null) identities.recordReconciliation(link, result)
            }
        }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
            futures.map { it.getNow(DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.FAILED)) }
        }
    }

    fun findByPlayerUuid(playerUuid: UUID): DiscordIdentityLink? = identities.findByPlayerUuid(playerUuid)

    fun findByDiscordUserId(discordUserId: String): DiscordIdentityLink? = identities.findByDiscordUserId(discordUserId)

    private fun reconcileVerified(
        completion: DiscordChallengeCompletionResult.Linked,
    ): CompletableFuture<DiscordVerificationWorkflowResult> =
        roles.reconcile(completion.link).thenApply { result ->
            identities.recordReconciliation(completion.link, result)
            DiscordVerificationWorkflowResult.Verified(completion.link, completion.idempotent, result)
        }

    private fun completeRecovery(
        prepared: DiscordChallengeCompletionResult.RecoveryPrepared,
    ): CompletableFuture<DiscordVerificationWorkflowResult> =
        roles.clearManagedRoles(prepared.currentLink).thenCompose { clearResult ->
            identities.recordReconciliation(prepared.currentLink, clearResult)
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
                        identities.recordReconciliation(recovered.newLink, reconcileResult)
                        DiscordVerificationWorkflowResult.Recovered(recovered.newLink, reconcileResult)
                    }
                DiscordRecoveryCompletionResult.Conflict -> completed(DiscordVerificationWorkflowResult.Conflict)
                DiscordRecoveryCompletionResult.Unavailable -> completed(DiscordVerificationWorkflowResult.Unavailable)
            }
        }

    private fun clearThenUnlink(link: DiscordIdentityLink): CompletableFuture<DiscordVerificationWorkflowResult> =
        roles.clearManagedRoles(link).thenApply { clearResult ->
            identities.recordReconciliation(link, clearResult)
            if (!clearResult.successful) {
                return@thenApply DiscordVerificationWorkflowResult.RoleFailure(clearResult)
            }
            when (val result = identities.completeUnlink(link.playerUuid, link.discordUserId)) {
                is DiscordUnlinkResult.Unlinked -> DiscordVerificationWorkflowResult.Unlinked(result.previousLink)
                DiscordUnlinkResult.NotLinked -> DiscordVerificationWorkflowResult.NotLinked
                DiscordUnlinkResult.Conflict -> DiscordVerificationWorkflowResult.Conflict
                DiscordUnlinkResult.Unavailable -> DiscordVerificationWorkflowResult.Unavailable
            }
        }

    private fun <T> completed(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)
}
