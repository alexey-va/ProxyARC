package ru.arc.social

import org.slf4j.LoggerFactory
import ru.arc.discord.DiscordBot
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.RedisRequestReplyChannel
import ru.arc.telegram.TelegramBot
import ru.arc.velocity.Velocity
import java.util.UUID

/**
 * Proxy owned read-only status endpoint. It reads the already durable provider
 * stores and returns only tri-state status, never provider account identifiers.
 */
class SocialIdentityStatusBridge(
    private val redis: RedisOperations,
) : AutoCloseable {
    private var channel: RedisRequestReplyChannel<SocialIdentityStatusWire>? = null

    @Synchronized
    fun start() {
        close()
        channel = RedisRequestReplyChannel(
            redis = redis,
            channel = SocialIdentityStatusWire.CHANNEL,
            codec = SocialIdentityStatusWire.codec(),
            // Backend identities are the only requesters. The proxy itself is
            // intentionally excluded from the request origin policy.
            originAllowed = { origin -> origin.isBackendOrigin() },
            requestId = SocialIdentityStatusWire::requestId,
            replyTo = SocialIdentityStatusWire::replyTo,
            replyAllowed = { request, reply, origin ->
                origin.equals(PROXY_ORIGIN, ignoreCase = true) &&
                    reply.isReply() &&
                    reply.replyTo == request.requestId &&
                    reply.requestId == request.requestId &&
                    reply.playerId == request.playerId
            },
            timeoutMillis = REQUEST_TIMEOUT_MS,
            maxPending = MAX_PENDING,
            onMessage = ::handleRequest,
            onHandlerFailure = { error -> log.warn("Social status request handler failed", error) },
        )
    }

    @Synchronized
    override fun close() {
        channel?.close()
        channel = null
    }

    private fun handleRequest(message: SocialIdentityStatusWire, origin: String) {
        if (!message.isRequest()) return
        val playerId = runCatching { UUID.fromString(message.playerId) }.getOrNull() ?: return
        val response = SocialIdentityStatusWire.reply(
            message,
            discord = discordStatus(Velocity.discordBot, playerId),
            telegram = telegramStatus(Velocity.telegramBot, playerId),
        )
        runCatching { channel?.publish(response) }
            .onFailure { log.warn("Could not publish social status reply for {}", playerId, it) }
    }

    private fun discordStatus(bot: DiscordBot?, playerId: UUID): String = when {
        bot == null || !bot.isVerificationEnabled() -> SocialIdentityStatusWire.UNAVAILABLE
        bot.findIdentityByPlayer(playerId) != null -> SocialIdentityStatusWire.LINKED
        else -> SocialIdentityStatusWire.UNLINKED
    }

    private fun telegramStatus(bot: TelegramBot?, playerId: UUID): String = when {
        bot == null || !bot.isIdentityEnabled() -> SocialIdentityStatusWire.UNAVAILABLE
        bot.findIdentityByPlayer(playerId) != null -> SocialIdentityStatusWire.LINKED
        else -> SocialIdentityStatusWire.UNLINKED
    }

    private fun String.isBackendOrigin(): Boolean =
        length in 1..32 && matches(BACKEND_ORIGIN) && !equals(PROXY_ORIGIN, ignoreCase = true)

    private companion object {
        const val PROXY_ORIGIN = "proxy"
        const val REQUEST_TIMEOUT_MS = 2_000L
        const val MAX_PENDING = 4
        val BACKEND_ORIGIN = Regex("[A-Za-z0-9_-]+")
        private val log = LoggerFactory.getLogger(SocialIdentityStatusBridge::class.java)
    }
}
