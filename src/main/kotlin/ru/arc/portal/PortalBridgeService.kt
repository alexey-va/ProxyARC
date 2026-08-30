package ru.arc.portal

import com.google.gson.Gson
import org.slf4j.Logger
import ru.arc.Common
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class PortalChatSource {
    MINECRAFT,
    DISCORD,
    TELEGRAM,
}

enum class PortalChatChannel {
    GAME,
    COMMUNITY,
}

data class PortalChatMessage(
    val sourceEventId: String,
    val source: PortalChatSource,
    val channel: PortalChatChannel,
    val authorUuid: UUID?,
    val authorName: String,
    val content: String,
    val createdAt: Long,
)

data class PortalPresencePlayer(
    val minecraftUuid: UUID,
    val minecraftName: String,
    val server: String?,
)

enum class PortalIdentityProvider {
    DISCORD,
    TELEGRAM,
}

data class PortalExternalIdentity(
    val providerUserId: String,
    val minecraftUuid: UUID,
    val minecraftName: String,
    val linkedAt: Long,
    val updatedAt: Long,
)

internal fun interface PortalHttpTransport {
    fun post(
        endpoint: URI,
        bearerToken: String,
        jsonBody: String,
        timeoutMillis: Long,
    ): CompletionStage<Int>
}

internal class JdkPortalHttpTransport(
    connectTimeoutMillis: Long,
) : PortalHttpTransport {
    private val client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun post(
        endpoint: URI,
        bearerToken: String,
        jsonBody: String,
        timeoutMillis: Long,
    ): CompletionStage<Int> {
        val request =
            HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Authorization", "Bearer $bearerToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build()
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .thenApply(HttpResponse<Void>::statusCode)
    }
}

/**
 * Bounded, one-way publisher from Velocity into the portal integration API.
 * Chat gameplay never waits for HTTP; saturation drops portal copies only and
 * leaves Minecraft, Discord and Telegram delivery untouched.
 */
class PortalBridgeService internal constructor(
    private val config: PortalBridgeConfig,
    private val logger: Logger,
    private val transport: PortalHttpTransport = JdkPortalHttpTransport(config.connectTimeoutMillis),
    private val gson: Gson = Common.gson,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)
    private val lastWarningAt = AtomicLong(0)

    init {
        config.validate()
    }

    fun publishChat(message: PortalChatMessage): Boolean {
        if (message.authorName.isBlank() || message.content.isBlank()) return false
        val payload =
            mapOf(
                "sourceEventId" to message.sourceEventId.take(128),
                "source" to message.source.name.lowercase(),
                "channel" to message.channel.name.lowercase(),
                "authorUuid" to message.authorUuid?.toString(),
                "authorName" to message.authorName.take(64),
                "content" to message.content.take(500),
                "createdAt" to message.createdAt,
            )
        return publish("/api/v1/integrations/chat", payload)
    }

    fun publishPresence(
        capturedAt: Long,
        players: Collection<PortalPresencePlayer>,
    ): Boolean {
        val payload =
            mapOf(
                "capturedAt" to capturedAt,
                "players" to
                    players.take(500).map { player ->
                        mapOf(
                            "minecraftUuid" to player.minecraftUuid.toString(),
                            "minecraftName" to player.minecraftName,
                            "server" to player.server,
                        )
                    },
            )
        return publish("/api/v1/integrations/presence", payload)
    }

    fun publishIdentitySnapshot(
        provider: PortalIdentityProvider,
        capturedAt: Long,
        identities: Collection<PortalExternalIdentity>,
    ): Boolean {
        val payload =
            mapOf(
                "provider" to provider.name.lowercase(),
                "capturedAt" to capturedAt,
                "identities" to
                    identities.take(10_000).map { identity ->
                        mapOf(
                            "providerUserId" to identity.providerUserId,
                            "minecraftUuid" to identity.minecraftUuid.toString(),
                            "minecraftName" to identity.minecraftName,
                            "linkedAt" to identity.linkedAt,
                            "updatedAt" to identity.updatedAt,
                        )
                    },
            )
        return publish("/api/v1/integrations/identities", payload)
    }

    internal fun inFlightCount(): Int = inFlight.get()

    override fun close() {
        closed.set(true)
    }

    private fun publish(
        path: String,
        payload: Any,
    ): Boolean {
        if (closed.get()) return false
        val active = inFlight.incrementAndGet()
        if (active > config.maxInFlight) {
            inFlight.decrementAndGet()
            warnDegraded(path, "saturated")
            return false
        }

        val request =
            try {
                transport.post(
                    endpoint = URI.create(config.baseUrl + path),
                    bearerToken = config.bridgeToken,
                    jsonBody = gson.toJson(payload),
                    timeoutMillis = config.requestTimeoutMillis,
                )
            } catch (error: Exception) {
                inFlight.decrementAndGet()
                warnDegraded(path, error.javaClass.simpleName)
                return false
            }

        request.whenComplete { status, error ->
            inFlight.decrementAndGet()
            when {
                error != null ->
                    warnDegraded(path, error.javaClass.simpleName)
                status !in 200..299 ->
                    warnDegraded(path, "http-$status")
            }
        }
        return true
    }

    private fun warnDegraded(
        path: String,
        reason: String,
    ) {
        val now = System.currentTimeMillis()
        val previous = lastWarningAt.get()
        if (now - previous < 30_000 || !lastWarningAt.compareAndSet(previous, now)) return
        logger.warn("Portal bridge degraded endpoint={} reason={}", path, reason)
    }
}
