package ru.arc.discord

import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal class DiscordLinkProtectionService(
    private val config: DiscordIntegrationConfig,
    private val notifications: DiscordSecurityNotifier,
    private val store: DiscordIntegrationStore,
    private val scheduler: ScheduledExecutorService,
) : AutoCloseable {
    private val pending = ConcurrentHashMap<String, PendingTransfer>()

    fun guard(prepared: DiscordChallengeCompletionResult.RecoveryPrepared): CompletableFuture<Boolean> {
        if (config.linkProtectionDelaySeconds == 0L) return CompletableFuture.completedFuture(true)
        val token = prepared.challengeId
        val future = CompletableFuture<Boolean>()
        val transfer = PendingTransfer(prepared.currentLink.discordUserId, prepared.currentLink.playerName, future)
        val previous = pending.putIfAbsent(token, transfer)
        if (previous != null) return previous.future

        store.recordSecurityEvent(
            "link-transfer-pending",
            prepared.currentLink.discordUserId,
            prepared.currentLink.playerName,
            prepared.newDiscordUserId,
        )
        notifications.notifySecurityAction(
            prepared.currentLink.discordUserId,
            config.messages.text(
                "security-link-pending",
                "player" to DiscordTextSafety.markdown(prepared.currentLink.playerName, 16),
                "seconds" to config.linkProtectionDelaySeconds.toString(),
            ),
            cancelButtonId(token),
            config.messages.text("security-link-cancel"),
        ).whenComplete { delivered, error ->
            if (delivered == true && error == null) {
                scheduleCompletion(token, transfer)
            } else {
                failUndeliverable(token, transfer)
            }
        }
        return future
    }

    private fun scheduleCompletion(
        token: String,
        transfer: PendingTransfer,
    ) {
        if (pending[token] !== transfer) return
        runCatching {
            scheduler.schedule(
                {
                    pending.remove(token, transfer)
                    transfer.future.complete(true)
                },
                config.linkProtectionDelaySeconds,
                TimeUnit.SECONDS,
            )
        }.onFailure { error ->
            pending.remove(token, transfer)
            transfer.future.complete(false)
            log.warn("Discord link protection scheduling failed: {}", error.javaClass.simpleName)
        }
    }

    private fun failUndeliverable(
        token: String,
        transfer: PendingTransfer,
    ) {
        if (!pending.remove(token, transfer)) return
        transfer.future.complete(false)
        runCatching {
            store.recordSecurityEvent(
                "link-transfer-notification-failed",
                transfer.previousDiscordUserId,
                transfer.playerName,
                token,
            )
        }.onFailure { log.warn("Could not audit failed Discord link notification: {}", it.javaClass.simpleName) }
        notifications.alert(
            config.messages.text(
                "security-link-delivery-failed-alert",
                "player" to DiscordTextSafety.markdown(transfer.playerName, 16),
            ),
        )
    }

    fun cancel(
        buttonId: String,
        discordUserId: String,
    ): Boolean {
        val token = buttonId.removePrefix(BUTTON_PREFIX).takeIf { it.isNotBlank() } ?: return false
        val transfer = pending[token] ?: return false
        if (transfer.previousDiscordUserId != discordUserId) return false
        if (!pending.remove(token, transfer)) return false
        val cancelled = transfer.future.complete(false)
        if (cancelled) {
            runCatching {
                store.recordSecurityEvent(
                    "link-transfer-cancelled",
                    discordUserId,
                    transfer.playerName,
                    token,
                )
            }.onFailure { log.warn("Could not audit cancelled Discord link transfer: {}", it.javaClass.simpleName) }
            notifications.alert(
                config.messages.text(
                    "security-link-cancelled-alert",
                    "player" to DiscordTextSafety.markdown(transfer.playerName, 16),
                ),
            )
        }
        return cancelled
    }

    override fun close() {
        pending.values.forEach { it.future.complete(false) }
        pending.clear()
    }

    private data class PendingTransfer(
        val previousDiscordUserId: String,
        val playerName: String,
        val future: CompletableFuture<Boolean>,
    )

    companion object {
        const val BUTTON_PREFIX = "rc:link:cancel:"
        private val log = LoggerFactory.getLogger(DiscordLinkProtectionService::class.java)

        fun cancelButtonId(challengeId: String): String = (BUTTON_PREFIX + challengeId).take(100)
    }
}
