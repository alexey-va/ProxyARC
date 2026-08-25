package ru.arc.discord

import ru.arc.metrics.core.MetricPoint
import java.util.EnumMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal enum class DiscordVerificationOperation(val label: String) {
    LINK_CHALLENGE("link_challenge"),
    RECOVERY_CHALLENGE("recovery_challenge"),
    DISCORD_COMPLETE("discord_complete"),
    UNLINK("unlink"),
}

internal enum class DiscordVerificationOutcome(val label: String) {
    SUCCESS("success"),
    IDEMPOTENT("idempotent"),
    PARTIAL("partial"),
    RATE_LIMITED("rate_limited"),
    INVALID("invalid"),
    ALREADY_LINKED("already_linked"),
    NOT_LINKED("not_linked"),
    CONFLICT("conflict"),
    ROLE_FAILURE("role_failure"),
    UNAVAILABLE("unavailable"),
    FAILED("failed"),
}

internal enum class DiscordRoleSyncTrigger(val label: String) {
    VERIFY("verify"),
    RECOVERY("recovery"),
    UNLINK("unlink"),
    SERVER_CONNECT("server_connect"),
    LUCKPERMS_EVENT("luckperms_event"),
    INITIAL("initial"),
    PERIODIC("periodic"),
    ADMIN("admin"),
}

internal data class DiscordIdentityStats(
    val storageAvailable: Boolean,
    val linkedAccounts: Int,
    val pendingChallenges: Int,
)

internal data class DiscordRoleSyncDiagnostic(
    val attemptedAt: Long,
    val trigger: DiscordRoleSyncTrigger,
    val result: DiscordRoleReconcileResult,
)

/** Bounded, value-safe diagnostics shared by commands, logs and Prometheus snapshots. */
internal class DiscordVerificationTelemetry(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val verificationResults =
        EnumMap<DiscordVerificationOperation, EnumMap<DiscordVerificationOutcome, AtomicLong>>(
            DiscordVerificationOperation::class.java,
        ).apply {
            DiscordVerificationOperation.entries.forEach { operation ->
                put(
                    operation,
                    EnumMap<DiscordVerificationOutcome, AtomicLong>(DiscordVerificationOutcome::class.java).apply {
                        DiscordVerificationOutcome.entries.forEach { outcome -> put(outcome, AtomicLong()) }
                    },
                )
            }
        }
    private val roleSyncResults =
        EnumMap<DiscordRoleReconcileResult.Status, AtomicLong>(DiscordRoleReconcileResult.Status::class.java).apply {
            DiscordRoleReconcileResult.Status.entries.forEach { status -> put(status, AtomicLong()) }
        }
    private val diagnostics = ConcurrentHashMap<UUID, DiscordRoleSyncDiagnostic>()
    private val lastRoleSyncAttemptAt = AtomicLong()
    private val lastSuccessfulRoleSyncAt = AtomicLong()

    fun recordVerification(
        operation: DiscordVerificationOperation,
        outcome: DiscordVerificationOutcome,
    ) {
        verificationResults.getValue(operation).getValue(outcome).incrementAndGet()
    }

    fun recordRoleSync(
        link: DiscordIdentityLink,
        trigger: DiscordRoleSyncTrigger,
        result: DiscordRoleReconcileResult,
    ) {
        val now = clock()
        val bounded = result.copy(reason = result.reason?.sanitizeReason())
        diagnostics[link.playerUuid] = DiscordRoleSyncDiagnostic(now, trigger, bounded)
        roleSyncResults.getValue(result.status).incrementAndGet()
        lastRoleSyncAttemptAt.set(now)
        if (result.successful) lastSuccessfulRoleSyncAt.set(now)
    }

    fun diagnostic(playerUuid: UUID): DiscordRoleSyncDiagnostic? = diagnostics[playerUuid]

    fun removeIdentity(playerUuid: UUID) {
        diagnostics.remove(playerUuid)
    }

    fun snapshot(identity: DiscordIdentityStats): List<MetricPoint> {
        val now = clock()
        val lastSuccess = lastSuccessfulRoleSyncAt.get()
        return buildList {
            add(
                MetricPoint(
                    "arc_discord_verification_storage_ready",
                    "Discord verification identity storage availability",
                    if (identity.storageAvailable) 1.0 else 0.0,
                ),
            )
            add(
                MetricPoint(
                    "arc_discord_identity_links",
                    "Linked Minecraft and Discord identities",
                    identity.linkedAccounts.toDouble(),
                ),
            )
            add(
                MetricPoint(
                    "arc_discord_verification_pending_challenges",
                    "Unexpired Discord verification challenges awaiting completion",
                    identity.pendingChallenges.toDouble(),
                ),
            )
            add(
                MetricPoint(
                    "arc_discord_role_sync_last_attempt_timestamp_seconds",
                    "Last Discord role reconciliation attempt timestamp",
                    lastRoleSyncAttemptAt.get() / 1_000.0,
                ),
            )
            add(
                MetricPoint(
                    "arc_discord_role_sync_last_success_timestamp_seconds",
                    "Last successful Discord role reconciliation timestamp",
                    lastSuccess / 1_000.0,
                ),
            )
            add(
                MetricPoint(
                    "arc_discord_role_sync_lag_seconds",
                    "Seconds since the last successful Discord role reconciliation",
                    if (lastSuccess == 0L) 0.0 else ((now - lastSuccess).coerceAtLeast(0L) / 1_000.0),
                ),
            )
            roleSyncResults.forEach { (status, count) ->
                add(
                    MetricPoint(
                        "arc_discord_role_sync_results",
                        "Cumulative Discord role reconciliation outcomes since process start",
                        count.get().toDouble(),
                        mapOf("status" to status.name.lowercase(Locale.ROOT)),
                    ),
                )
            }
            verificationResults.forEach { (operation, outcomes) ->
                outcomes.forEach { (outcome, count) ->
                    add(
                        MetricPoint(
                            "arc_discord_verification_results",
                            "Cumulative Discord identity workflow outcomes since process start",
                            count.get().toDouble(),
                            mapOf("operation" to operation.label, "outcome" to outcome.label),
                        ),
                    )
                }
            }
        }
    }

    private fun String.sanitizeReason(): String =
        asSequence()
            .filter { it >= ' ' && it != '\u007f' }
            .take(MAX_REASON_LENGTH)
            .joinToString("")
            .ifBlank { "unspecified" }

    companion object {
        private const val MAX_REASON_LENGTH = 96
    }
}
