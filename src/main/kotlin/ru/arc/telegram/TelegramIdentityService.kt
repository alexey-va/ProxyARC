package ru.arc.telegram

import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

class TelegramIdentityService internal constructor(
    private val store: TelegramIdentityStore,
    private val config: TelegramConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val codeGenerator: (Int) -> String = ::secureCode,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    fun issueChallenge(
        playerUuid: UUID,
        playerName: String,
    ): TelegramChallengeIssueResult {
        if (!PLAYER_NAME.matches(playerName)) return TelegramChallengeIssueResult.Unavailable
        val now = clock()
        return safely(TelegramChallengeIssueResult.Unavailable) { store.mutate { state ->
            prune(state, now)
            state.links.firstOrNull { it.playerUuid == playerUuid.toString() }?.let { existing ->
                return@mutate TelegramChallengeIssueResult.AlreadyLinked(existing.toDomain())
            }
            val rate = rateWindow(state, "issue:$playerUuid", now, config.identityIssueWindowSeconds)
            val retryAt = rate.windowStartedAt + config.identityIssueWindowSeconds * 1_000
            if (now < rate.nextAllowedAt) return@mutate TelegramChallengeIssueResult.RateLimited(rate.nextAllowedAt)
            if (rate.count >= config.identityMaxIssuesPerWindow) {
                return@mutate TelegramChallengeIssueResult.RateLimited(retryAt)
            }
            rate.count++
            rate.nextAllowedAt = now + config.identityIssueCooldownSeconds * 1_000
            state.challenges.removeIf { it.playerUuid == playerUuid.toString() }
            val rawCode = uniqueCode(state)
            val expiresAt = now + config.identityCodeTtlSeconds * 1_000
            state.challenges +=
                StoredTelegramChallenge(
                    id = idGenerator(),
                    playerUuid = playerUuid.toString(),
                    playerName = playerName,
                    codeHash = hash(normalizeCode(rawCode)),
                    createdAt = now,
                    expiresAt = expiresAt,
                )
            audit(state, now, "challenge", "issued", playerUuid.toString(), playerName)
            TelegramChallengeIssueResult.Issued(formatCode(rawCode), expiresAt)
        } }
    }

    fun completeChallenge(
        rawCode: String,
        telegramUserId: Long,
        telegramUsername: String?,
        telegramDisplayName: String,
    ): TelegramChallengeCompletionResult {
        if (telegramUserId <= 0) return TelegramChallengeCompletionResult.InvalidOrExpired
        val now = clock()
        val normalizedUsername = normalizeUsername(telegramUsername)
        val displayName = normalizeDisplayName(telegramDisplayName)
        return safely(TelegramChallengeCompletionResult.Unavailable) { store.mutate { state ->
            prune(state, now)
            val rate = rateWindow(state, "attempt:$telegramUserId", now, config.identityAttemptWindowSeconds)
            if (rate.count >= config.identityMaxAttemptsPerWindow) {
                return@mutate TelegramChallengeCompletionResult.RateLimited(
                    rate.windowStartedAt + config.identityAttemptWindowSeconds * 1_000,
                )
            }
            rate.count++
            val challenge = state.challenges.firstOrNull { it.codeHash == hash(normalizeCode(rawCode)) }
            if (challenge == null || challenge.expiresAt <= now) {
                audit(state, now, "complete", "invalid", telegramUserId = telegramUserId)
                return@mutate TelegramChallengeCompletionResult.InvalidOrExpired
            }
            challenge.completedTelegramUserId?.let { completedId ->
                val linked = state.links.firstOrNull { it.playerUuid == challenge.playerUuid }
                if (completedId == telegramUserId && linked?.telegramUserId == telegramUserId) {
                    return@mutate TelegramChallengeCompletionResult.Linked(linked.toDomain(), idempotent = true)
                }
                return@mutate TelegramChallengeCompletionResult.InvalidOrExpired
            }
            val minecraftLink = state.links.firstOrNull { it.playerUuid == challenge.playerUuid }
            if (minecraftLink != null) return@mutate TelegramChallengeCompletionResult.MinecraftAlreadyLinked
            if (state.links.any { it.telegramUserId == telegramUserId }) {
                return@mutate TelegramChallengeCompletionResult.TelegramAlreadyLinked
            }
            claimUsername(state, telegramUserId, normalizedUsername, now)
            val link =
                StoredTelegramIdentityLink(
                    playerUuid = challenge.playerUuid,
                    playerName = challenge.playerName,
                    telegramUserId = telegramUserId,
                    telegramUsername = normalizedUsername,
                    telegramDisplayName = displayName,
                    linkedAt = now,
                    updatedAt = now,
                )
            state.links += link
            challenge.completedTelegramUserId = telegramUserId
            challenge.completedAt = now
            state.rates.removeIf { it.key == "attempt:$telegramUserId" }
            audit(state, now, "link", "completed", link.playerUuid, link.playerName, telegramUserId)
            TelegramChallengeCompletionResult.Linked(link.toDomain(), idempotent = false)
        } }
    }

    fun unlinkByPlayer(playerUuid: UUID): TelegramUnlinkResult =
        safely(TelegramUnlinkResult.Unavailable) { store.mutate { state ->
            val link = state.links.firstOrNull { it.playerUuid == playerUuid.toString() }
                ?: return@mutate TelegramUnlinkResult.NotLinked
            state.links.remove(link)
            state.challenges.removeIf { it.playerUuid == link.playerUuid }
            audit(state, clock(), "unlink", "minecraft", link.playerUuid, link.playerName, link.telegramUserId)
            TelegramUnlinkResult.Unlinked(link.toDomain())
        } }

    fun unlinkByTelegram(telegramUserId: Long): TelegramUnlinkResult =
        safely(TelegramUnlinkResult.Unavailable) { store.mutate { state ->
            val link = state.links.firstOrNull { it.telegramUserId == telegramUserId }
                ?: return@mutate TelegramUnlinkResult.NotLinked
            state.links.remove(link)
            state.challenges.removeIf { it.playerUuid == link.playerUuid }
            audit(state, clock(), "unlink", "telegram", link.playerUuid, link.playerName, telegramUserId)
            TelegramUnlinkResult.Unlinked(link.toDomain())
        } }

    fun findByPlayerUuid(playerUuid: UUID): TelegramIdentityLink? =
        safely(null) {
            store.read { state -> state.links.firstOrNull { it.playerUuid == playerUuid.toString() }?.toDomain() }
        }

    fun findByTelegramUserId(telegramUserId: Long): TelegramIdentityLink? =
        safely(null) { store.read { state -> state.links.firstOrNull { it.telegramUserId == telegramUserId }?.toDomain() } }

    fun allLinks(): List<TelegramIdentityLink> =
        safely(emptyList()) { store.read { state -> state.links.map { it.toDomain() } } }

    fun findByTelegramUsername(username: String): TelegramIdentityLink? {
        val normalized = normalizeUsername(username) ?: return null
        return safely(null) {
            store.read { state ->
                state.links.firstOrNull { it.telegramUsername?.lowercase(Locale.ROOT) == normalized.lowercase(Locale.ROOT) }
                    ?.toDomain()
            }
        }
    }

    fun updatePlayerName(
        playerUuid: UUID,
        playerName: String,
    ): TelegramIdentityLink? {
        if (!PLAYER_NAME.matches(playerName)) return null
        val current = findByPlayerUuid(playerUuid) ?: return null
        if (current.playerName == playerName) return current
        return safely(null) { store.mutate { state ->
            val link = state.links.firstOrNull { it.playerUuid == playerUuid.toString() } ?: return@mutate null
            if (link.playerName != playerName) {
                link.playerName = playerName
                link.updatedAt = clock()
                audit(state, link.updatedAt, "player-name", "updated", link.playerUuid, playerName, link.telegramUserId)
            }
            link.toDomain()
        } }
    }

    fun updateTelegramProfile(
        telegramUserId: Long,
        username: String?,
        displayName: String,
    ): TelegramIdentityLink? {
        val normalizedUsername = normalizeUsername(username)
        val normalizedDisplayName = normalizeDisplayName(displayName)
        val current = findByTelegramUserId(telegramUserId) ?: return null
        if (current.telegramUsername == normalizedUsername && current.telegramDisplayName == normalizedDisplayName) {
            return current
        }
        return safely(null) { store.mutate { state ->
            val link = state.links.firstOrNull { it.telegramUserId == telegramUserId } ?: return@mutate null
            if (link.telegramUsername != normalizedUsername || link.telegramDisplayName != normalizedDisplayName) {
                claimUsername(state, telegramUserId, normalizedUsername, clock())
                link.telegramUsername = normalizedUsername
                link.telegramDisplayName = normalizedDisplayName
                link.updatedAt = clock()
            }
            link.toDomain()
        } }
    }

    private fun claimUsername(
        state: StoredTelegramIdentityState,
        ownerTelegramUserId: Long,
        username: String?,
        now: Long,
    ) {
        if (username == null) return
        state.links
            .filter {
                it.telegramUserId != ownerTelegramUserId &&
                    it.telegramUsername.equals(username, ignoreCase = true)
            }.forEach { stale ->
                stale.telegramUsername = null
                stale.updatedAt = now
            }
    }

    private fun uniqueCode(state: StoredTelegramIdentityState): String {
        repeat(32) {
            val candidate = normalizeCode(codeGenerator(config.identityCodeLength))
            require(candidate.length == config.identityCodeLength) { "generated Telegram code has invalid length" }
            if (state.challenges.none { it.codeHash == hash(candidate) }) return candidate
        }
        error("could not generate unique Telegram verification code")
    }

    private fun rateWindow(
        state: StoredTelegramIdentityState,
        key: String,
        now: Long,
        windowSeconds: Int,
    ): StoredTelegramRateWindow {
        val windowMillis = windowSeconds * 1_000L
        val current = state.rates.firstOrNull { it.key == key }
        if (current == null) {
            return StoredTelegramRateWindow(key, now, 0, 0).also(state.rates::add)
        }
        if (now - current.windowStartedAt >= windowMillis) {
            current.windowStartedAt = now
            current.count = 0
            current.nextAllowedAt = 0
        }
        return current
    }

    private fun prune(
        state: StoredTelegramIdentityState,
        now: Long,
    ) {
        state.challenges.removeIf { it.expiresAt <= now && it.completedTelegramUserId == null }
        state.rates.removeIf { now - it.windowStartedAt > MAX_RATE_AGE_MS }
        while (state.audit.size >= 2_000) state.audit.removeAt(0)
    }

    private fun audit(
        state: StoredTelegramIdentityState,
        now: Long,
        event: String,
        outcome: String,
        playerUuid: String? = null,
        playerName: String? = null,
        telegramUserId: Long? = null,
    ) {
        while (state.audit.size >= 2_000) state.audit.removeAt(0)
        state.audit += StoredTelegramIdentityAudit(now, event, outcome, playerUuid, playerName, telegramUserId)
    }

    private fun <T> safely(
        fallback: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (error: Exception) {
            log.error("Telegram identity operation failed: {}", error.javaClass.simpleName)
            fallback
        }

    companion object {
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        private val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray()
        private val RANDOM = SecureRandom()
        private val log = LoggerFactory.getLogger(TelegramIdentityService::class.java)
        private const val MAX_RATE_AGE_MS = 24L * 60L * 60L * 1_000L

        private fun secureCode(length: Int): String =
            buildString(length) { repeat(length) { append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.size)]) } }

        private fun normalizeCode(value: String): String =
            value.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)

        private fun formatCode(value: String): String = value.chunked(4).joinToString("-")

        private fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }

        private fun normalizeUsername(value: String?): String? =
            value?.removePrefix("@")?.trim()?.takeIf { Regex("[A-Za-z0-9_]{1,32}").matches(it) }

        private fun normalizeDisplayName(value: String): String =
            value.filterNot(Char::isISOControl).trim().take(128).ifBlank { "Telegram user" }
    }
}
