package ru.arc.discord

import java.util.UUID

internal enum class DiscordChallengePurpose {
    LINK,
    RECOVER,
}

internal data class DiscordIdentityLink(
    val playerUuid: UUID,
    val playerName: String,
    val discordUserId: String,
    val linkedAt: Long,
    val updatedAt: Long,
)

internal sealed interface DiscordChallengeIssueResult {
    data class Issued(
        val code: String,
        val purpose: DiscordChallengePurpose,
        val expiresAt: Long,
    ) : DiscordChallengeIssueResult

    data class AlreadyLinked(val link: DiscordIdentityLink) : DiscordChallengeIssueResult

    data object NotLinked : DiscordChallengeIssueResult

    data class RateLimited(val retryAt: Long) : DiscordChallengeIssueResult

    data object Unavailable : DiscordChallengeIssueResult
}

internal sealed interface DiscordChallengeCompletionResult {
    data class Linked(
        val link: DiscordIdentityLink,
        val idempotent: Boolean,
    ) : DiscordChallengeCompletionResult

    data class RecoveryPrepared(
        val challengeId: String,
        val currentLink: DiscordIdentityLink,
        val newDiscordUserId: String,
    ) : DiscordChallengeCompletionResult

    data object InvalidOrExpired : DiscordChallengeCompletionResult

    data object MinecraftAlreadyLinked : DiscordChallengeCompletionResult

    data object DiscordAlreadyLinked : DiscordChallengeCompletionResult

    data class RateLimited(val retryAt: Long) : DiscordChallengeCompletionResult

    data object Unavailable : DiscordChallengeCompletionResult
}

internal sealed interface DiscordRecoveryCompletionResult {
    data class Recovered(
        val previousLink: DiscordIdentityLink,
        val newLink: DiscordIdentityLink,
        val idempotent: Boolean = false,
    ) : DiscordRecoveryCompletionResult

    data object Conflict : DiscordRecoveryCompletionResult

    data object Unavailable : DiscordRecoveryCompletionResult
}

internal sealed interface DiscordUnlinkResult {
    data class Unlinked(val previousLink: DiscordIdentityLink) : DiscordUnlinkResult

    data object NotLinked : DiscordUnlinkResult

    data object Conflict : DiscordUnlinkResult

    data object Unavailable : DiscordUnlinkResult
}

internal data class DiscordRoleReconcileResult(
    val status: Status,
    val addedRoleIds: Set<String> = emptySet(),
    val removedRoleIds: Set<String> = emptySet(),
    val nicknameChanged: Boolean = false,
    val reason: String? = null,
) {
    enum class Status {
        UPDATED,
        UNCHANGED,
        NOT_READY,
        MEMBER_NOT_FOUND,
        CONFIG_ERROR,
        HIERARCHY_BLOCKED,
        PROVIDER_UNAVAILABLE,
        FAILED,
    }

    val successful: Boolean get() = status == Status.UPDATED || status == Status.UNCHANGED
}
