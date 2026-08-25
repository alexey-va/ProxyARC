package ru.arc.discord

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal interface DiscordSecurityNotifier {
    fun notifySecurityAction(
        discordUserId: String,
        message: String,
        buttonId: String,
        buttonLabel: String,
    ): CompletableFuture<Boolean>

    fun alert(message: String)
}

internal class DiscordNotificationService(
    private val session: DiscordSession,
    private val config: DiscordIntegrationConfig,
    private val store: DiscordIntegrationStore,
    private val identityByPlayerName: (String) -> DiscordIdentityLink?,
    private val identityByPlayerUuid: (UUID) -> DiscordIdentityLink?,
    private val clock: () -> Long = System::currentTimeMillis,
) : DiscordSecurityNotifier {
    private val rateWindows = ConcurrentHashMap<String, DeliveryWindow>()

    fun preferences(discordUserId: String): DiscordNotificationPreferences = store.preferences(discordUserId)

    fun toggle(
        discordUserId: String,
        kind: DiscordNotificationKind,
    ): DiscordNotificationPreferences = store.toggle(discordUserId, kind)

    fun notifyMentions(rawMessage: String) {
        val bounded = DiscordTextSafety.plain(rawMessage, 400)
        PLAYER_MENTION.findAll(rawMessage).map { it.groupValues[1] }.distinctBy(String::lowercase).forEach { playerName ->
            val link = identityByPlayerName(playerName) ?: return@forEach
            deliverOptIn(
                link,
                DiscordNotificationKind.MENTIONS,
                config.messages.text("mention-dm", "message" to DiscordTextSafety.quote(bounded)),
            )
        }
    }

    fun notifyAuctionSold(
        sellerUuid: UUID?,
        sellerName: String,
        item: String,
        amount: Int,
        price: String,
        buyerName: String,
    ) {
        val link = sellerUuid?.let(identityByPlayerUuid) ?: identityByPlayerName(sellerName) ?: return
        deliverOptIn(
            link,
            DiscordNotificationKind.AUCTION,
            config.messages.text(
                "auction-sold-dm",
                "item" to DiscordTextSafety.markdown(item, 100),
                "amount" to amount.coerceAtLeast(1).toString(),
                "price" to DiscordTextSafety.markdown(price, 80),
                "buyer" to DiscordTextSafety.markdown(buyerName, 16),
            ),
        )
    }

    fun notifyTicketReply(
        reporter: String,
        ticketId: String,
        url: String,
    ) {
        val link = identityByPlayerName(reporter) ?: return
        deliverOptIn(
            link,
            DiscordNotificationKind.TICKETS,
            config.messages.text(
                "ticket-reply-dm",
                "ticket" to DiscordTextSafety.markdown(ticketId, 40),
                "url" to url.take(300),
            ),
        )
    }

    fun notifyPunishment(
        playerUuid: UUID,
        playerName: String,
        type: String,
        status: String,
    ) {
        val link = identityByPlayerUuid(playerUuid) ?: identityByPlayerName(playerName) ?: return
        deliverOptIn(
            link,
            DiscordNotificationKind.PUNISHMENTS,
            config.messages.text(
                "punishment-dm",
                "type" to DiscordTextSafety.markdown(type, 32),
                "player" to DiscordTextSafety.markdown(playerName, 16),
                "status" to DiscordTextSafety.markdown(status, 100),
            ),
        )
    }

    fun notifyInvite(
        playerName: String,
        message: String,
    ): CompletableFuture<Boolean> {
        val link = identityByPlayerName(playerName) ?: return CompletableFuture.completedFuture(false)
        if (!store.preferences(link.discordUserId).enabled(DiscordNotificationKind.INVITES)) {
            return CompletableFuture.completedFuture(false)
        }
        return deliver(
            link.discordUserId,
            config.messages.text("invite-dm", "message" to message.take(400)),
            security = false,
        )
    }

    fun notifyEventParticipants(
        event: DiscordGameEvent,
        message: String,
    ) {
        val enabled = store.enabledDiscordUserIds(DiscordNotificationKind.EVENTS)
        event.participantDiscordIds.forEach { discordUserId ->
            if (discordUserId in enabled) {
                deliver(discordUserId, message, security = false)
            }
        }
    }

    fun notifySecurity(
        discordUserId: String,
        message: String,
    ): CompletableFuture<Boolean> = deliver(discordUserId, message, security = true)

    override fun notifySecurityAction(
        discordUserId: String,
        message: String,
        buttonId: String,
        buttonLabel: String,
    ): CompletableFuture<Boolean> =
        deliver(
            discordUserId,
            message,
            security = true,
            action = ActionRow.of(Button.danger(buttonId.take(100), buttonLabel.take(80))),
        )

    override fun alert(message: String) {
        val channelId = config.alertsChannelId ?: return
        val channel = session.jda()?.getTextChannelById(channelId) ?: return
        channel.sendMessage(safeMessage(message)).queue(
            {},
            { error -> log.warn("Discord integration alert delivery failed: {}", error.javaClass.simpleName) },
        )
    }

    private fun deliverOptIn(
        link: DiscordIdentityLink,
        kind: DiscordNotificationKind,
        message: String,
    ) {
        if (!store.preferences(link.discordUserId).enabled(kind)) return
        deliver(link.discordUserId, message, security = false)
    }

    private fun deliver(
        discordUserId: String,
        message: String,
        security: Boolean,
        action: ActionRow? = null,
    ): CompletableFuture<Boolean> {
        if (!security && !allow(discordUserId)) return CompletableFuture.completedFuture(false)
        val jda = session.jda() ?: return CompletableFuture.completedFuture(false)
        val result = CompletableFuture<Boolean>()
        jda.retrieveUserById(discordUserId).queue(
            { user ->
                user.openPrivateChannel().queue(
                    { channel ->
                        channel.sendMessage(safeMessage(message, action)).queue(
                            { result.complete(true) },
                            { error ->
                                log.debug("Discord DM delivery failed: {}", error.javaClass.simpleName)
                                result.complete(false)
                            },
                        )
                    },
                    { error ->
                        log.debug("Discord private channel unavailable: {}", error.javaClass.simpleName)
                        result.complete(false)
                    },
                )
            },
            { error ->
                log.debug("Discord notification user lookup failed: {}", error.javaClass.simpleName)
                result.complete(false)
            },
        )
        return result
    }

    private fun allow(discordUserId: String): Boolean {
        val now = clock()
        if (rateWindows.size >= MAX_RATE_WINDOWS) {
            rateWindows.entries.removeIf { now - it.value.startedAt >= 120_000 }
            if (rateWindows.size >= MAX_RATE_WINDOWS && !rateWindows.containsKey(discordUserId)) return false
        }
        val window = rateWindows.compute(discordUserId) { _, existing ->
            if (existing == null || now - existing.startedAt >= 60_000) DeliveryWindow(now, 1)
            else existing.copy(count = existing.count + 1)
        } ?: return false
        return window.count <= config.notificationRatePerMinute
    }

    private fun safeMessage(
        content: String,
        action: ActionRow? = null,
    ): net.dv8tion.jda.api.utils.messages.MessageCreateData =
        MessageCreateBuilder()
            .setContent(content.take(Message.MAX_CONTENT_LENGTH))
            .also { builder -> action?.let { builder.setComponents(listOf(it)) } }
            .setAllowedMentions(emptySet())
            .build()

    private data class DeliveryWindow(val startedAt: Long, val count: Int)

    companion object {
        private val log = LoggerFactory.getLogger(DiscordNotificationService::class.java)
        private val PLAYER_MENTION = Regex("(?<![\\w<])@([A-Za-z0-9_]{1,16})")
        private const val MAX_RATE_WINDOWS = 10_000
    }
}

internal object DiscordTextSafety {
    private val MARKDOWN = Regex("([\\\\`*_{}\\[\\]()#+.!|>~-])")
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f]")

    fun markdown(
        raw: String,
        maxLength: Int,
    ): String = MARKDOWN.replace(plain(raw, maxLength), "\\\\$1")

    fun plain(
        raw: String,
        maxLength: Int,
    ): String = CONTROL.replace(raw, " ").replace(Regex("\\s+"), " ").trim().take(maxLength)

    fun quote(raw: String): String = plain(raw, 400).replace("\n", " ")
}
