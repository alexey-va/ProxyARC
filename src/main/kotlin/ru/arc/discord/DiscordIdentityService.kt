package ru.arc.discord

import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

internal class DiscordIdentityService(
    private val store: DiscordIdentityStore,
    private val config: DiscordVerificationConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val codeGenerator: (Int) -> String = ::secureCode,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    fun isAvailable(): Boolean = store.isAvailable()

    fun storageFailureClass(): String? = store.failureClass()

    fun issueLinkChallenge(
        playerUuid: UUID,
        playerName: String,
    ): DiscordChallengeIssueResult =
        issueChallenge(playerUuid, playerName, DiscordChallengePurpose.LINK)

    fun issueRecoveryChallenge(
        playerUuid: UUID,
        playerName: String,
    ): DiscordChallengeIssueResult =
        issueChallenge(playerUuid, playerName, DiscordChallengePurpose.RECOVER)

    fun completeChallenge(
        rawCode: String,
        discordUserId: String,
    ): DiscordChallengeCompletionResult {
        if (!DiscordVerificationConfig.validSnowflake(discordUserId)) {
            return DiscordChallengeCompletionResult.InvalidOrExpired
        }
        val now = clock()
        val codeHash = hashCode(normalizeCode(rawCode))
        return safely(DiscordChallengeCompletionResult.Unavailable) {
            store.mutate { state ->
                prune(state, now)
                val attemptRate = rateWindow(state.attemptRates, discordUserId, now, config.attemptWindowSeconds)
                if (attemptRate.count >= config.maxAttemptsPerWindow) {
                    return@mutate DiscordChallengeCompletionResult.RateLimited(
                        attemptRate.windowStartedAt + config.attemptWindowSeconds * 1_000,
                    )
                }
                attemptRate.count++
                val challenge = state.challenges.firstOrNull { it.codeHash == codeHash }
                if (challenge == null || challenge.expiresAt <= now) {
                    appendAudit(
                        state,
                        now,
                        event = "challenge-complete",
                        outcome = "invalid",
                        discordUserId = discordUserId,
                    )
                    return@mutate DiscordChallengeCompletionResult.InvalidOrExpired
                }
                challenge.completedDiscordUserId?.let { completedDiscordUserId ->
                    if (completedDiscordUserId != discordUserId) {
                        return@mutate DiscordChallengeCompletionResult.InvalidOrExpired
                    }
                    val completedLink =
                        state.links.firstOrNull {
                            it.playerUuid == challenge.playerUuid && it.discordUserId == discordUserId
                        }
                    if (completedLink != null) {
                        state.attemptRates.removeIf { it.key == discordUserId }
                        return@mutate DiscordChallengeCompletionResult.Linked(
                            completedLink.toDomain(),
                            idempotent = true,
                        )
                    }
                    return@mutate DiscordChallengeCompletionResult.InvalidOrExpired
                }
                if (challenge.claimedByDiscordUserId != null &&
                    challenge.claimedByDiscordUserId != discordUserId &&
                    challenge.claimExpiresAt > now
                ) {
                    return@mutate DiscordChallengeCompletionResult.InvalidOrExpired
                }
                val purpose = runCatching { DiscordChallengePurpose.valueOf(challenge.purpose) }.getOrNull()
                    ?: return@mutate DiscordChallengeCompletionResult.InvalidOrExpired
                when (purpose) {
                    DiscordChallengePurpose.LINK -> completeLink(state, challenge, discordUserId, now)
                    DiscordChallengePurpose.RECOVER -> prepareRecovery(state, challenge, discordUserId, now)
                }
            }
        }
    }

    fun completeRecovery(
        challengeId: String,
        newDiscordUserId: String,
    ): DiscordRecoveryCompletionResult {
        val now = clock()
        return safely(DiscordRecoveryCompletionResult.Unavailable) {
            store.mutate { state ->
                prune(state, now)
                val challenge = state.challenges.firstOrNull { it.id == challengeId }
                    ?: return@mutate DiscordRecoveryCompletionResult.Conflict
                val linked = state.links.firstOrNull { it.playerUuid == challenge.playerUuid }
                if (challenge.completedDiscordUserId == newDiscordUserId &&
                    linked?.discordUserId == newDiscordUserId
                ) {
                    val current = linked.toDomain()
                    val previousDiscordUserId = challenge.previousDiscordUserId
                        ?: return@mutate DiscordRecoveryCompletionResult.Conflict
                    return@mutate DiscordRecoveryCompletionResult.Recovered(
                        current.copy(discordUserId = previousDiscordUserId),
                        current,
                        idempotent = true,
                    )
                }
                if (challenge.purpose != DiscordChallengePurpose.RECOVER.name ||
                    challenge.claimedByDiscordUserId != newDiscordUserId ||
                    challenge.claimExpiresAt <= now
                ) {
                    return@mutate DiscordRecoveryCompletionResult.Conflict
                }
                val current = state.links.firstOrNull { it.playerUuid == challenge.playerUuid }
                    ?: return@mutate DiscordRecoveryCompletionResult.Conflict
                if (current.discordUserId != challenge.previousDiscordUserId ||
                    state.links.any { it.discordUserId == newDiscordUserId && it.playerUuid != challenge.playerUuid }
                ) {
                    return@mutate DiscordRecoveryCompletionResult.Conflict
                }
                val previous = current.toDomain()
                current.discordUserId = newDiscordUserId
                current.playerName = challenge.playerName
                current.updatedAt = now
                challenge.claimedByDiscordUserId = null
                challenge.claimExpiresAt = 0
                challenge.completedDiscordUserId = newDiscordUserId
                challenge.completedAt = now
                state.attemptRates.removeIf { it.key == newDiscordUserId }
                appendAudit(
                    state,
                    now,
                    event = "recover",
                    outcome = "completed",
                    playerUuid = current.playerUuid,
                    playerName = current.playerName,
                    discordUserId = newDiscordUserId,
                    previousDiscordUserId = previous.discordUserId,
                )
                DiscordRecoveryCompletionResult.Recovered(previous, current.toDomain(), idempotent = false)
            }
        }
    }

    fun releaseRecoveryClaim(
        challengeId: String,
        newDiscordUserId: String,
        reason: String,
    ) {
        safely(Unit) {
            store.mutate { state ->
                val challenge = state.challenges.firstOrNull { it.id == challengeId }
                if (challenge?.claimedByDiscordUserId == newDiscordUserId) {
                    challenge.claimedByDiscordUserId = null
                    challenge.claimExpiresAt = 0
                    appendAudit(
                        state,
                        clock(),
                        event = "recover",
                        outcome = "released",
                        playerUuid = challenge.playerUuid,
                        playerName = challenge.playerName,
                        discordUserId = newDiscordUserId,
                        previousDiscordUserId = challenge.previousDiscordUserId,
                        detail = reason.take(80),
                    )
                }
            }
        }
    }

    fun completeUnlink(
        playerUuid: UUID,
        expectedDiscordUserId: String,
    ): DiscordUnlinkResult {
        val now = clock()
        return safely(DiscordUnlinkResult.Unavailable) {
            store.mutate { state ->
                val stored = state.links.firstOrNull { it.playerUuid == playerUuid.toString() }
                    ?: return@mutate DiscordUnlinkResult.NotLinked
                if (stored.discordUserId != expectedDiscordUserId) {
                    return@mutate DiscordUnlinkResult.Conflict
                }
                val previous = stored.toDomain()
                state.links.remove(stored)
                state.challenges.removeIf { it.playerUuid == stored.playerUuid }
                state.issueRates.removeIf { it.key == stored.playerUuid }
                state.attemptRates.removeIf { it.key == stored.discordUserId }
                appendAudit(
                    state,
                    now,
                    event = "unlink",
                    outcome = "completed",
                    playerUuid = stored.playerUuid,
                    playerName = stored.playerName,
                    discordUserId = stored.discordUserId,
                )
                DiscordUnlinkResult.Unlinked(previous)
            }
        }
    }

    fun findByPlayerUuid(playerUuid: UUID): DiscordIdentityLink? =
        safely(null) {
            store.read { state -> state.links.firstOrNull { it.playerUuid == playerUuid.toString() }?.toDomain() }
        }

    fun findByDiscordUserId(discordUserId: String): DiscordIdentityLink? =
        safely(null) {
            store.read { state -> state.links.firstOrNull { it.discordUserId == discordUserId }?.toDomain() }
        }

    fun findByPlayerName(playerName: String): DiscordIdentityLink? {
        val normalized = normalizeName(playerName)
        return safely(null) {
            store.read { state ->
                state.links.firstOrNull { normalizeName(it.playerName) == normalized }?.toDomain()
            }
        }
    }

    fun allLinks(): List<DiscordIdentityLink> =
        safely(emptyList()) { store.read { state -> state.links.map { it.toDomain() } } }

    fun updatePlayerName(
        playerUuid: UUID,
        playerName: String,
    ): DiscordIdentityLink? {
        val now = clock()
        return safely(null) {
            store.mutate { state ->
                val link = state.links.firstOrNull { it.playerUuid == playerUuid.toString() }
                    ?: return@mutate null
                if (link.playerName != playerName) {
                    link.playerName = playerName
                    link.updatedAt = now
                    appendAudit(
                        state,
                        now,
                        event = "player-name",
                        outcome = "updated",
                        playerUuid = link.playerUuid,
                        playerName = playerName,
                        discordUserId = link.discordUserId,
                    )
                }
                link.toDomain()
            }
        }
    }

    fun recordReconciliation(
        link: DiscordIdentityLink,
        result: DiscordRoleReconcileResult,
    ) {
        if (result.status == DiscordRoleReconcileResult.Status.UNCHANGED) return
        safely(Unit) {
            store.mutate { state ->
                appendAudit(
                    state,
                    clock(),
                    event = "role-reconcile",
                    outcome = result.status.name.lowercase(Locale.ROOT),
                    playerUuid = link.playerUuid.toString(),
                    playerName = link.playerName,
                    discordUserId = link.discordUserId,
                    detail = buildString {
                        append("added=").append(result.addedRoleIds.size)
                        append(",removed=").append(result.removedRoleIds.size)
                        append(",nickname=").append(result.nicknameChanged)
                        result.reason?.let { append(",reason=").append(it.take(48)) }
                    },
                )
            }
        }
    }

    private fun issueChallenge(
        playerUuid: UUID,
        playerName: String,
        purpose: DiscordChallengePurpose,
    ): DiscordChallengeIssueResult {
        val now = clock()
        return safely(DiscordChallengeIssueResult.Unavailable) {
            store.mutate { state ->
                prune(state, now)
                val existing = state.links.firstOrNull { it.playerUuid == playerUuid.toString() }
                if (purpose == DiscordChallengePurpose.LINK && existing != null) {
                    return@mutate DiscordChallengeIssueResult.AlreadyLinked(existing.toDomain())
                }
                if (purpose == DiscordChallengePurpose.RECOVER && existing == null) {
                    return@mutate DiscordChallengeIssueResult.NotLinked
                }
                val rate = rateWindow(state.issueRates, playerUuid.toString(), now, config.issueWindowSeconds)
                val windowRetryAt = rate.windowStartedAt + config.issueWindowSeconds * 1_000
                if (now < rate.nextAllowedAt) {
                    return@mutate DiscordChallengeIssueResult.RateLimited(rate.nextAllowedAt)
                }
                if (rate.count >= config.maxIssuesPerWindow) {
                    return@mutate DiscordChallengeIssueResult.RateLimited(windowRetryAt)
                }
                rate.count++
                rate.nextAllowedAt = now + config.issueCooldownSeconds * 1_000
                state.challenges.removeIf { it.playerUuid == playerUuid.toString() }
                val rawCode = uniqueChallengeCode(state)
                val codeHash = hashCode(normalizeCode(rawCode))
                val expiresAt = now + config.codeTtlSeconds * 1_000
                state.challenges +=
                    StoredDiscordChallenge(
                        id = idGenerator(),
                        purpose = purpose.name,
                        playerUuid = playerUuid.toString(),
                        playerName = playerName,
                        previousDiscordUserId = existing?.discordUserId,
                        codeHash = codeHash,
                        createdAt = now,
                        expiresAt = expiresAt,
                    )
                appendAudit(
                    state,
                    now,
                    event = "challenge-issue",
                    outcome = purpose.name.lowercase(Locale.ROOT),
                    playerUuid = playerUuid.toString(),
                    playerName = playerName,
                    discordUserId = existing?.discordUserId,
                )
                DiscordChallengeIssueResult.Issued(formatCode(rawCode), purpose, expiresAt)
            }
        }
    }

    private fun uniqueChallengeCode(state: DiscordIdentityState): String {
        repeat(MAX_CODE_COLLISION_ATTEMPTS) {
            val candidate = normalizeCode(codeGenerator(config.codeLength))
            require(candidate.length == config.codeLength) { "generated verification code has invalid length" }
            val digest = hashCode(candidate)
            if (state.challenges.none { it.codeHash == digest }) return candidate
        }
        error("could not generate a unique verification code")
    }

    private fun completeLink(
        state: DiscordIdentityState,
        challenge: StoredDiscordChallenge,
        discordUserId: String,
        now: Long,
    ): DiscordChallengeCompletionResult {
        val minecraftLink = state.links.firstOrNull { it.playerUuid == challenge.playerUuid }
        if (minecraftLink != null) {
            if (minecraftLink.discordUserId == discordUserId) {
                challenge.completedDiscordUserId = discordUserId
                challenge.completedAt = now
                state.attemptRates.removeIf { it.key == discordUserId }
                return DiscordChallengeCompletionResult.Linked(minecraftLink.toDomain(), idempotent = true)
            }
            appendConflictAudit(state, challenge, discordUserId, now, "minecraft-linked")
            state.challenges.removeIf { it.id == challenge.id }
            return DiscordChallengeCompletionResult.MinecraftAlreadyLinked
        }
        if (state.links.any { it.discordUserId == discordUserId }) {
            appendConflictAudit(state, challenge, discordUserId, now, "discord-linked")
            return DiscordChallengeCompletionResult.DiscordAlreadyLinked
        }
        val link =
            StoredDiscordIdentityLink(
                playerUuid = challenge.playerUuid,
                playerName = challenge.playerName,
                discordUserId = discordUserId,
                linkedAt = now,
                updatedAt = now,
            )
        state.links += link
        challenge.completedDiscordUserId = discordUserId
        challenge.completedAt = now
        state.attemptRates.removeIf { it.key == discordUserId }
        appendAudit(
            state,
            now,
            event = "link",
            outcome = "completed",
            playerUuid = link.playerUuid,
            playerName = link.playerName,
            discordUserId = link.discordUserId,
        )
        return DiscordChallengeCompletionResult.Linked(link.toDomain(), idempotent = false)
    }

    private fun prepareRecovery(
        state: DiscordIdentityState,
        challenge: StoredDiscordChallenge,
        discordUserId: String,
        now: Long,
    ): DiscordChallengeCompletionResult {
        val current = state.links.firstOrNull { it.playerUuid == challenge.playerUuid }
            ?: return DiscordChallengeCompletionResult.InvalidOrExpired
        if (current.discordUserId != challenge.previousDiscordUserId) {
            state.challenges.removeIf { it.id == challenge.id }
            return DiscordChallengeCompletionResult.MinecraftAlreadyLinked
        }
        if (current.discordUserId == discordUserId) {
            challenge.completedDiscordUserId = discordUserId
            challenge.completedAt = now
            state.attemptRates.removeIf { it.key == discordUserId }
            return DiscordChallengeCompletionResult.Linked(current.toDomain(), idempotent = true)
        }
        if (state.links.any { it.discordUserId == discordUserId }) {
            appendConflictAudit(state, challenge, discordUserId, now, "discord-linked")
            return DiscordChallengeCompletionResult.DiscordAlreadyLinked
        }
        challenge.claimedByDiscordUserId = discordUserId
        challenge.claimExpiresAt = minOf(challenge.expiresAt, now + CLAIM_TTL_MS)
        appendAudit(
            state,
            now,
            event = "recover",
            outcome = "prepared",
            playerUuid = challenge.playerUuid,
            playerName = challenge.playerName,
            discordUserId = discordUserId,
            previousDiscordUserId = current.discordUserId,
        )
        return DiscordChallengeCompletionResult.RecoveryPrepared(
            challenge.id,
            current.toDomain(),
            discordUserId,
        )
    }

    private fun rateWindow(
        rates: MutableList<StoredRateWindow>,
        key: String,
        now: Long,
        windowSeconds: Long,
    ): StoredRateWindow {
        val windowMs = windowSeconds * 1_000
        val existing = rates.firstOrNull { it.key == key }
        if (existing == null) {
            if (rates.size >= MAX_RATE_WINDOWS) {
                rates.minByOrNull(StoredRateWindow::windowStartedAt)?.let(rates::remove)
            }
            return StoredRateWindow(key = key, windowStartedAt = now).also(rates::add)
        }
        if (now - existing.windowStartedAt >= windowMs) {
            existing.windowStartedAt = now
            existing.count = 0
            existing.nextAllowedAt = 0
        }
        return existing
    }

    private fun prune(
        state: DiscordIdentityState,
        now: Long,
    ) {
        state.challenges.removeIf { it.expiresAt <= now }
        state.challenges.forEach { challenge ->
            if (challenge.claimExpiresAt <= now) {
                challenge.claimedByDiscordUserId = null
                challenge.claimExpiresAt = 0
            }
        }
        val maxWindowMs = maxOf(config.issueWindowSeconds, config.attemptWindowSeconds) * 2_000
        state.issueRates.removeIf { now - it.windowStartedAt > maxWindowMs }
        state.attemptRates.removeIf { now - it.windowStartedAt > maxWindowMs }
        trimAudit(state)
    }

    private fun appendConflictAudit(
        state: DiscordIdentityState,
        challenge: StoredDiscordChallenge,
        discordUserId: String,
        now: Long,
        detail: String,
    ) {
        appendAudit(
            state,
            now,
            event = "challenge-complete",
            outcome = "conflict",
            playerUuid = challenge.playerUuid,
            playerName = challenge.playerName,
            discordUserId = discordUserId,
            previousDiscordUserId = challenge.previousDiscordUserId,
            detail = detail,
        )
    }

    private fun appendAudit(
        state: DiscordIdentityState,
        timestamp: Long,
        event: String,
        outcome: String,
        playerUuid: String? = null,
        playerName: String? = null,
        discordUserId: String? = null,
        previousDiscordUserId: String? = null,
        detail: String? = null,
    ) {
        state.audit +=
            StoredDiscordIdentityAudit(
                timestamp = timestamp,
                event = event,
                outcome = outcome,
                playerUuid = playerUuid,
                playerName = playerName,
                discordUserId = discordUserId,
                previousDiscordUserId = previousDiscordUserId,
                detail = detail,
            )
        trimAudit(state)
    }

    private fun trimAudit(state: DiscordIdentityState) {
        if (state.audit.size > MAX_AUDIT_EVENTS) {
            state.audit = state.audit.takeLast(MAX_AUDIT_EVENTS).toMutableList()
        }
    }

    private fun StoredDiscordIdentityLink.toDomain(): DiscordIdentityLink =
        DiscordIdentityLink(
            playerUuid = UUID.fromString(playerUuid),
            playerName = playerName,
            discordUserId = discordUserId,
            linkedAt = linkedAt,
            updatedAt = updatedAt,
        )

    private fun <T> safely(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (error: DiscordIdentityStoreUnavailableException) {
            fallback
        } catch (error: Exception) {
            log.warn("Discord identity operation failed: {}", error.javaClass.simpleName)
            fallback
        }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordIdentityService::class.java)
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val CLAIM_TTL_MS = 60_000L
        private const val MAX_AUDIT_EVENTS = 2_000
        private const val MAX_RATE_WINDOWS = 10_000
        private const val MAX_CODE_COLLISION_ATTEMPTS = 8
        private val secureRandom = SecureRandom()

        private fun secureCode(length: Int): String =
            buildString(length) {
                repeat(length) { append(CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)]) }
            }

        internal fun normalizeCode(raw: String): String =
            raw.uppercase(Locale.ROOT).filter { it in CODE_ALPHABET }

        internal fun formatCode(raw: String): String {
            val normalized = normalizeCode(raw)
            return normalized.chunked(4).joinToString("-")
        }

        internal fun hashCode(normalizedCode: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(normalizedCode.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun normalizeName(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
}
