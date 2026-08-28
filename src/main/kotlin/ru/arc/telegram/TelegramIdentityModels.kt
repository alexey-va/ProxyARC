package ru.arc.telegram

import java.util.UUID

data class TelegramIdentityLink(
    val playerUuid: UUID,
    val playerName: String,
    val telegramUserId: Long,
    val telegramUsername: String?,
    val telegramDisplayName: String,
    val linkedAt: Long,
    val updatedAt: Long,
)

sealed interface TelegramChallengeIssueResult {
    data class Issued(
        val code: String,
        val expiresAt: Long,
    ) : TelegramChallengeIssueResult

    data class AlreadyLinked(val link: TelegramIdentityLink) : TelegramChallengeIssueResult

    data class RateLimited(val retryAt: Long) : TelegramChallengeIssueResult

    data object Unavailable : TelegramChallengeIssueResult
}

sealed interface TelegramChallengeCompletionResult {
    data class Linked(
        val link: TelegramIdentityLink,
        val idempotent: Boolean,
    ) : TelegramChallengeCompletionResult

    data object InvalidOrExpired : TelegramChallengeCompletionResult

    data object MinecraftAlreadyLinked : TelegramChallengeCompletionResult

    data object TelegramAlreadyLinked : TelegramChallengeCompletionResult

    data class RateLimited(val retryAt: Long) : TelegramChallengeCompletionResult

    data object Unavailable : TelegramChallengeCompletionResult
}

sealed interface TelegramUnlinkResult {
    data class Unlinked(val previousLink: TelegramIdentityLink) : TelegramUnlinkResult

    data object NotLinked : TelegramUnlinkResult

    data object Unavailable : TelegramUnlinkResult
}
