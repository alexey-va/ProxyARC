package ru.arc.portal

import com.google.gson.Gson
import org.slf4j.Logger
import ru.arc.Common
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
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

data class PortalOutboundChatMessage(
    val id: Long,
    val sourceEventId: String,
    val channel: PortalChatChannel,
    val authorUuid: UUID,
    val authorName: String,
    val content: String,
    val createdAt: Long,
)

enum class PortalOutboundPollStart {
    STARTED,
    ALREADY_RUNNING,
    CLOSED,
    FAILED_TO_START,
}

private const val MAX_OUTBOUND_RESPONSE_BYTES = 64 * 1024

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

internal data class PortalHttpGetResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface PortalHttpGetTransport {
    fun get(
        endpoint: URI,
        bearerToken: String,
        timeoutMillis: Long,
    ): CompletionStage<PortalHttpGetResponse>
}

internal class JdkPortalHttpGetTransport(
    connectTimeoutMillis: Long,
) : PortalHttpGetTransport {
    private val client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun get(
        endpoint: URI,
        bearerToken: String,
        timeoutMillis: Long,
    ): CompletionStage<PortalHttpGetResponse> {
        val request =
            HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Authorization", "Bearer $bearerToken")
                .header("Accept", "application/json")
                .GET()
                .build()
        val boundedBody =
            HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(),
                MAX_OUTBOUND_RESPONSE_BYTES.toLong(),
            )
        return client.sendAsync(request, boundedBody)
            .thenApply { response -> PortalHttpGetResponse(response.statusCode(), response.body()) }
    }
}

/**
 * Bounded publisher into the portal plus a non-overlapping durable-outbox
 * drainer from the portal. Existing chat gameplay never waits for HTTP.
 */
class PortalBridgeService internal constructor(
    private val config: PortalBridgeConfig,
    private val logger: Logger,
    private val transport: PortalHttpTransport = JdkPortalHttpTransport(config.connectTimeoutMillis),
    private val getTransport: PortalHttpGetTransport = JdkPortalHttpGetTransport(config.connectTimeoutMillis),
    private val gson: Gson = Common.gson,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)
    private val lastWarningAt = AtomicLong(0)
    private val outboundPollInFlight = AtomicBoolean(false)

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

    /**
     * Starts one bounded outbox pull. Delivery and acknowledgement complete on
     * HTTP completion threads; another poll cannot overlap this lifecycle.
     * A delivered row is acknowledged only after the local sinks accepted it.
     */
    fun pollOutboundChat(deliver: (PortalOutboundChatMessage) -> Boolean): PortalOutboundPollStart {
        if (closed.get()) return PortalOutboundPollStart.CLOSED
        if (!outboundPollInFlight.compareAndSet(false, true)) {
            return PortalOutboundPollStart.ALREADY_RUNNING
        }
        val request =
            try {
                getTransport.get(
                    endpoint = URI.create(config.baseUrl + OUTBOUND_PATH),
                    bearerToken = config.bridgeToken,
                    timeoutMillis = config.requestTimeoutMillis,
                )
        } catch (error: Exception) {
            outboundPollInFlight.set(false)
            warnDegraded(OUTBOUND_PATH, error.javaClass.simpleName)
            return PortalOutboundPollStart.FAILED_TO_START
        }
        request.whenComplete { response, error ->
            when {
                error != null -> {
                    outboundPollInFlight.set(false)
                    warnDegraded(OUTBOUND_PATH, error.javaClass.simpleName)
                }
                closed.get() -> outboundPollInFlight.set(false)
                response.statusCode !in 200..299 -> {
                    outboundPollInFlight.set(false)
                    warnDegraded(OUTBOUND_PATH, "http-${response.statusCode}")
                }
                else -> handleOutboundBatch(response.body, deliver)
            }
        }
        return PortalOutboundPollStart.STARTED
    }

    internal fun inFlightCount(): Int = inFlight.get()

    override fun close() {
        closed.set(true)
    }

    private fun handleOutboundBatch(
        body: String,
        deliver: (PortalOutboundChatMessage) -> Boolean,
    ) {
        val messages =
            try {
                require(body.toByteArray(StandardCharsets.UTF_8).size <= MAX_OUTBOUND_RESPONSE_BYTES) {
                    "response-too-large"
                }
                val batch = gson.fromJson(body, PortalOutboundChatBatch::class.java)
                require(batch.messages.size <= MAX_OUTBOUND_MESSAGES) { "too-many-messages" }
                batch.messages.map(PortalOutboundChatWireMessage::validated)
            } catch (error: Exception) {
                outboundPollInFlight.set(false)
                warnDegraded(OUTBOUND_PATH, "invalid-response")
                return
            }
        val acknowledgements = mutableListOf<CompletableFuture<*>>()
        for (message in messages) {
            if (closed.get()) break
            val accepted = runCatching { deliver(message) }.getOrDefault(false)
            if (!accepted) {
                warnDegraded(OUTBOUND_PATH, "delivery-unavailable")
                continue
            }
            val acknowledgement =
                try {
                    transport.post(
                        endpoint = URI.create(config.baseUrl + "$OUTBOUND_PATH/${message.id}/ack"),
                        bearerToken = config.bridgeToken,
                        jsonBody = "{}",
                        timeoutMillis = config.requestTimeoutMillis,
                    ).toCompletableFuture()
                } catch (error: Exception) {
                    warnDegraded(OUTBOUND_PATH, error.javaClass.simpleName)
                    continue
                }
            acknowledgements +=
                acknowledgement.whenComplete { status, error ->
                    when {
                        error != null -> warnDegraded(OUTBOUND_PATH, error.javaClass.simpleName)
                        status !in 200..299 -> warnDegraded(OUTBOUND_PATH, "ack-http-$status")
                    }
                }
        }
        if (acknowledgements.isEmpty()) {
            outboundPollInFlight.set(false)
            return
        }
        CompletableFuture.allOf(*acknowledgements.toTypedArray()).whenComplete { _, _ ->
            outboundPollInFlight.set(false)
        }
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

    private data class PortalOutboundChatBatch(
        val messages: List<PortalOutboundChatWireMessage> = emptyList(),
    )

    private data class PortalOutboundChatWireMessage(
        val id: Long = 0,
        val sourceEventId: String = "",
        val channel: String = "",
        val authorUuid: String = "",
        val authorName: String = "",
        val content: String = "",
        val createdAt: Long = 0,
    ) {
        fun validated(): PortalOutboundChatMessage {
            require(id > 0)
            require(sourceEventId.isNotBlank() && sourceEventId.length <= 128)
            val parsedChannel = PortalChatChannel.valueOf(channel.uppercase())
            val parsedUuid = UUID.fromString(authorUuid)
            require(PLAYER_NAME.matches(authorName))
            require(content.isNotBlank() && content.length <= 300)
            require(content.none { it == '\n' || it == '\r' || it.code < 0x20 || it.code == 0x7f })
            require(createdAt > 0)
            return PortalOutboundChatMessage(
                id = id,
                sourceEventId = sourceEventId,
                channel = parsedChannel,
                authorUuid = parsedUuid,
                authorName = authorName,
                content = content,
                createdAt = createdAt,
            )
        }
    }

    private companion object {
        const val OUTBOUND_PATH = "/api/v1/integrations/chat/outbox"
        const val MAX_OUTBOUND_MESSAGES = 50
        val PLAYER_NAME = Regex("^[A-Za-z0-9_]{3,16}$")
    }
}
