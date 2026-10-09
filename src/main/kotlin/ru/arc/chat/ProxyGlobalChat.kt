package ru.arc.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.slf4j.LoggerFactory
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import ru.arc.telegram.TelegramCodexMessageReceipt
import ru.arc.velocity.Velocity
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture

enum class ProxyGlobalChatSource(val wireValue: String) {
    MINECRAFT("minecraft"),
    TELEGRAM("telegram"),
    DISCORD("discord"),
    CODEX("codex"),
}

data class ProxyGlobalChatRecord(
    val cursor: String,
    val timestamp: String,
    val source: String,
    val author: String,
    val playerUuid: String?,
    val content: String,
    val contentTruncated: Boolean,
)

data class ProxyGlobalChatPage(
    val instanceId: String,
    val messages: List<ProxyGlobalChatRecord>,
    val nextCursor: String,
    val historyGap: Boolean,
    val gapReason: String?,
)

data class ProxyGlobalChatSinkReceipt(
    val status: String,
    val messageId: Int? = null,
    val threadId: Int? = null,
    val chatId: String? = null,
    val recipients: Int? = null,
    val reason: String? = null,
) {
    fun toMap(): Map<String, Any?> =
        linkedMapOf<String, Any?>("status" to status).apply {
            messageId?.let { put("messageId", it) }
            threadId?.let { put("threadId", it) }
            chatId?.let { put("chatId", it) }
            recipients?.let { put("recipients", it) }
            reason?.let { put("reason", it) }
        }
}

data class ProxyGlobalChatSendReceipt(
    val requestId: String,
    val instanceId: String,
    val cursor: String,
    val timestamp: String,
    val deliveries: Map<String, ProxyGlobalChatSinkReceipt>,
) {
    fun toMap(replayed: Boolean): Map<String, Any?> =
        linkedMapOf(
            "requestId" to requestId,
            "instanceId" to instanceId,
            "cursor" to cursor,
            "timestamp" to timestamp,
            "deliveries" to deliveries.mapValues { it.value.toMap() },
            "replayed" to replayed,
        )
}

data class ProxyGlobalChatDeliveryResult(
    val minecraft: ProxyGlobalChatSinkReceipt,
    val discord: ProxyGlobalChatSinkReceipt,
    val telegram: ProxyGlobalChatSinkReceipt,
) {
    fun toMap(): Map<String, ProxyGlobalChatSinkReceipt> =
        linkedMapOf("minecraft" to minecraft, "discord" to discord, "telegram" to telegram)
}

fun interface ProxyGlobalChatDelivery {
    fun send(content: String): CompletableFuture<ProxyGlobalChatDeliveryResult>
}

sealed interface ProxyGlobalChatSubmission {
    data class Submitted(
        val receipt: CompletableFuture<ProxyGlobalChatSendReceipt>,
        val replayed: Boolean,
    ) : ProxyGlobalChatSubmission

    data class Rejected(val httpStatus: Int, val error: String) : ProxyGlobalChatSubmission
}

/**
 * Process-local global chat history and idempotency journal. The instance id deliberately changes
 * on process restart, so a client cannot accidentally retry an uncertain send into a new epoch.
 */
class ProxyGlobalChatService(
    private val historyCapacity: Int = DEFAULT_HISTORY_CAPACITY,
    private val receiptCapacity: Int = DEFAULT_RECEIPT_CAPACITY,
    instanceId: String = UUID.randomUUID().toString(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val records = ArrayDeque<StoredRecord>()
    private val receipts = LinkedHashMap<String, StoredReceipt>()
    private var nextSequence = 1L
    private var droppedRecords = 0L

    val instanceId: String = canonicalUuid(instanceId) ?: error("instanceId must be a canonical UUID")

    init {
        require(historyCapacity > 0) { "historyCapacity must be positive" }
        require(receiptCapacity > 0) { "receiptCapacity must be positive" }
    }

    fun record(
        source: ProxyGlobalChatSource,
        author: String,
        playerUuid: UUID?,
        content: String,
        timestampMillis: Long = clock(),
    ): ProxyGlobalChatRecord? {
        if (content.isBlank()) return null
        val boundedAuthor = truncateCodePoints(author.ifBlank { source.wireValue }, MAX_AUTHOR_CODE_POINTS, MAX_AUTHOR_UTF8_BYTES).first
        val boundedContent = truncateCodePoints(content, MAX_HISTORY_CONTENT_CODE_POINTS, MAX_HISTORY_CONTENT_UTF8_BYTES)
        return synchronized(lock) {
            append(
                source = source,
                author = boundedAuthor,
                playerUuid = playerUuid?.toString(),
                content = boundedContent.first,
                contentTruncated = boundedContent.second,
                timestampMillis = timestampMillis,
            )
        }
    }

    fun read(
        limit: Int = DEFAULT_READ_LIMIT,
        after: String? = null,
        player: String? = null,
    ): ProxyGlobalChatPage {
        require(limit in 1..MAX_READ_LIMIT) { "limit must be in 1..$MAX_READ_LIMIT" }
        require(player == null || player.length in 1..MAX_PLAYER_FILTER_LENGTH) {
            "player filter must contain 1..$MAX_PLAYER_FILTER_LENGTH characters"
        }
        require(player == null || player.none { Character.isISOControl(it) }) { "player filter must not contain control characters" }

        return synchronized(lock) {
            val currentSequence = nextSequence - 1
            val playerFilter = player?.trim()?.takeIf(String::isNotEmpty)
            val predicate: (StoredRecord) -> Boolean = { stored ->
                playerFilter == null ||
                    stored.record.author.equals(playerFilter, ignoreCase = true) ||
                    stored.record.playerUuid.equals(playerFilter, ignoreCase = true)
            }
            val oldestSequence = records.firstOrNull()?.sequence ?: nextSequence
            val cursor = after?.let(::parseCursor)
            var gapReason: String? = when {
                cursor == null && droppedRecords > 0 -> "buffer-truncated"
                cursor == null -> "instance-start"
                cursor.instanceId != instanceId -> "instance-changed"
                cursor.sequence < oldestSequence - 1 -> "buffer-truncated"
                else -> null
            }

            if (cursor != null && cursor.instanceId == instanceId) {
                require(cursor.sequence <= currentSequence) { "cursor is ahead of current history" }
            }

            val pageRecords =
                when {
                    cursor == null -> records.asSequence().filter(predicate).toList().takeLast(limit)
                    cursor.instanceId != instanceId -> records.asSequence().filter(predicate).toList().takeLast(limit)
                    else -> records.asSequence().filter { it.sequence > cursor.sequence && predicate(it) }.take(limit).toList()
                }

            val nextSequenceForClient =
                if (cursor == null || cursor.instanceId != instanceId) {
                    currentSequence
                } else if (pageRecords.size == limit) {
                    pageRecords.last().sequence
                } else {
                    currentSequence
                }
            if (cursor != null && cursor.instanceId == instanceId && cursor.sequence >= currentSequence) {
                gapReason = null
            }

            ProxyGlobalChatPage(
                instanceId = instanceId,
                messages = pageRecords.map(StoredRecord::record),
                nextCursor = cursorFor(nextSequenceForClient),
                historyGap = gapReason != null,
                gapReason = gapReason,
            )
        }
    }

    fun submit(
        requestId: String,
        requestInstanceId: String,
        content: String,
        delivery: ProxyGlobalChatDelivery,
    ): ProxyGlobalChatSubmission {
        val canonicalRequestId = canonicalUuid(requestId)
            ?: return ProxyGlobalChatSubmission.Rejected(400, "requestId must be a canonical UUID")
        val canonicalInstanceId = canonicalUuid(requestInstanceId)
            ?: return ProxyGlobalChatSubmission.Rejected(400, "instanceId must be a canonical UUID")
        validateOutgoingContent(content)?.let { error ->
            return ProxyGlobalChatSubmission.Rejected(400, error)
        }

        val prepared = synchronized(lock) {
            if (canonicalInstanceId != instanceId) {
                return@synchronized Preparation.Rejected(409, "instanceId-mismatch")
            }
            val existing = receipts[canonicalRequestId]
            if (existing != null) {
                return@synchronized if (existing.content == content) {
                    Preparation.Ready(existing.result, replayed = true)
                } else {
                    Preparation.Rejected(409, "requestId-already-used-with-different-content")
                }
            }
            if (receipts.size >= receiptCapacity) {
                return@synchronized Preparation.Rejected(429, "chat-send-receipts-exhausted")
            }

            val message =
                append(
                    source = ProxyGlobalChatSource.CODEX,
                    author = CODEX_AUTHOR,
                    playerUuid = null,
                    content = content,
                    contentTruncated = false,
                    timestampMillis = clock(),
                )
            val result = CompletableFuture<ProxyGlobalChatSendReceipt>()
            receipts[canonicalRequestId] = StoredReceipt(content, result)
            Preparation.Start(
                future = result,
                record = message,
            )
        }

        when (prepared) {
            is Preparation.Rejected -> return ProxyGlobalChatSubmission.Rejected(prepared.httpStatus, prepared.error)
            is Preparation.Ready -> return ProxyGlobalChatSubmission.Submitted(prepared.future, prepared.replayed)
            is Preparation.Start -> {
                val deliveryFuture =
                    try {
                        delivery.send(content)
                    } catch (_: Throwable) {
                        CompletableFuture.completedFuture(unknownDeliveryResult())
                    }
                deliveryFuture.whenComplete { deliveries, failure ->
                    val resolvedDeliveries = if (failure == null && deliveries != null) deliveries else unknownDeliveryResult()
                    prepared.future.complete(
                        ProxyGlobalChatSendReceipt(
                            requestId = canonicalRequestId,
                            instanceId = instanceId,
                            cursor = prepared.record.cursor,
                            timestamp = prepared.record.timestamp,
                            deliveries = resolvedDeliveries.toMap(),
                        ),
                    )
                }
                return ProxyGlobalChatSubmission.Submitted(prepared.future, replayed = false)
            }
        }
    }

    fun validateOutgoingContent(content: String): String? {
        val codePointCount = content.codePointCount(0, content.length)
        val byteCount = content.toByteArray(StandardCharsets.UTF_8).size
        if (content.isBlank() || codePointCount !in 1..MAX_OUTGOING_CODE_POINTS || byteCount > MAX_OUTGOING_UTF8_BYTES) {
            return "content must contain 1..$MAX_OUTGOING_CODE_POINTS characters and at most $MAX_OUTGOING_UTF8_BYTES UTF-8 bytes"
        }
        if (content.codePoints().anyMatch { Character.isISOControl(it) || it == 0x2028 || it == 0x2029 }) {
            return "content must not contain control characters or line breaks"
        }
        return null
    }

    private fun append(
        source: ProxyGlobalChatSource,
        author: String,
        playerUuid: String?,
        content: String,
        contentTruncated: Boolean,
        timestampMillis: Long,
    ): ProxyGlobalChatRecord {
        val sequence = nextSequence++
        val record =
            ProxyGlobalChatRecord(
                cursor = cursorFor(sequence),
                timestamp = Instant.ofEpochMilli(timestampMillis).toString(),
                source = source.wireValue,
                author = author,
                playerUuid = playerUuid,
                content = content,
                contentTruncated = contentTruncated,
            )
        records.addLast(StoredRecord(sequence, record))
        while (records.size > historyCapacity) {
            records.removeFirst()
            droppedRecords++
        }
        return record
    }

    private fun parseCursor(value: String): ParsedCursor {
        require(value.length <= MAX_CURSOR_LENGTH) { "invalid cursor" }
        val separator = value.lastIndexOf(':')
        require(separator > 0 && separator < value.lastIndex) { "invalid cursor" }
        val cursorInstanceId = requireNotNull(canonicalUuid(value.substring(0, separator))) { "invalid cursor" }
        val sequenceText = value.substring(separator + 1)
        val sequence = requireNotNull(sequenceText.toLongOrNull()?.takeIf { it >= 0 }) { "invalid cursor" }
        return ParsedCursor(cursorInstanceId, sequence)
    }

    private fun cursorFor(sequence: Long): String = "$instanceId:$sequence"

    private fun truncateCodePoints(value: String, maximumCodePoints: Int, maximumBytes: Int): Pair<String, Boolean> {
        var offset = 0
        var codePoints = 0
        var bytes = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val pointBytes =
                when {
                    codePoint <= 0x7F -> 1
                    codePoint <= 0x7FF -> 2
                    codePoint <= 0xFFFF -> 3
                    else -> 4
                }
            if (codePoints >= maximumCodePoints || bytes + pointBytes > maximumBytes) break
            codePoints++
            bytes += pointBytes
            offset += Character.charCount(codePoint)
        }
        return value.substring(0, offset) to (offset < value.length)
    }

    private sealed interface Preparation {
        data class Rejected(val httpStatus: Int, val error: String) : Preparation

        data class Ready(
            val future: CompletableFuture<ProxyGlobalChatSendReceipt>,
            val replayed: Boolean,
        ) : Preparation

        data class Start(
            val future: CompletableFuture<ProxyGlobalChatSendReceipt>,
            val record: ProxyGlobalChatRecord,
        ) : Preparation
    }

    private data class StoredRecord(val sequence: Long, val record: ProxyGlobalChatRecord)

    private data class StoredReceipt(
        val content: String,
        val result: CompletableFuture<ProxyGlobalChatSendReceipt>,
    )

    private data class ParsedCursor(val instanceId: String, val sequence: Long)

    companion object {
        const val DEFAULT_READ_LIMIT = 200
        const val MAX_READ_LIMIT = 1_000
        const val DEFAULT_HISTORY_CAPACITY = 5_000
        const val DEFAULT_RECEIPT_CAPACITY = 256
        const val MAX_OUTGOING_CODE_POINTS = 500
        const val MAX_OUTGOING_UTF8_BYTES = 2_000
        const val MAX_PLAYER_FILTER_LENGTH = 64
        private const val MAX_AUTHOR_CODE_POINTS = 96
        private const val MAX_AUTHOR_UTF8_BYTES = 384
        private const val MAX_HISTORY_CONTENT_CODE_POINTS = 4_096
        private const val MAX_HISTORY_CONTENT_UTF8_BYTES = 4_096
        private const val MAX_CURSOR_LENGTH = 64
        const val CODEX_AUTHOR = "Codex"

        private fun canonicalUuid(value: String): String? =
            runCatching { UUID.fromString(value).toString() }
                .getOrNull()
                ?.takeIf { it.equals(value, ignoreCase = true) }

        private fun unknownDeliveryResult() =
            ProxyGlobalChatDeliveryResult(
                minecraft = ProxyGlobalChatSinkReceipt("unknown", reason = "dispatch-failed"),
                discord = ProxyGlobalChatSinkReceipt("unknown", reason = "dispatch-failed"),
                telegram = ProxyGlobalChatSinkReceipt("unknown", reason = "dispatch-failed"),
            )
    }
}

/** Sends one fixed-identity Codex message through the existing global chat surfaces. */
class VelocityProxyGlobalChatDelivery(
    private val renderer: ExternalChatRenderer = ExternalChatRenderer(),
    private val configProvider: () -> Config = ProxyConfigs::main,
    private val minecraftSink: (Component) -> Int? = { message ->
        val proxy = Velocity.proxyServer
        if (proxy == null) null else Velocity.sendMessageToPlayers(proxy.allPlayers, message)
    },
    private val discordSink: (String) -> Boolean = { message ->
        Velocity.discordBot?.sendCodexChatMessage(message) ?: false
    },
    private val telegramSink: (String) -> CompletableFuture<TelegramCodexMessageReceipt?> = { message ->
        Velocity.telegramBot?.sendCodexChatMessage(message) ?: CompletableFuture.completedFuture(null)
    },
) : ProxyGlobalChatDelivery {
    override fun send(content: String): CompletableFuture<ProxyGlobalChatDeliveryResult> {
        val minecraft =
            renderer.render(null, ProxyGlobalChatService.CODEX_AUTHOR, Component.text(content)) { sender, body ->
                Component.empty()
                    .append(Component.text("\uE514").color(WHITE))
                    .append(Component.space())
                    .append(Component.text("|").color(DARK_GRAY))
                    .append(Component.space())
                    .append(sender)
                    .append(Component.space())
                    .append(body.color(MESSAGE_BEIGE))
            }.thenApply { rendered ->
                minecraftSink(rendered)?.let { recipients ->
                    ProxyGlobalChatSinkReceipt("accepted", recipients = recipients)
                } ?: ProxyGlobalChatSinkReceipt("unavailable", reason = "minecraft-proxy-unavailable")
            }.exceptionally { failure ->
                log.warn("Could not render Codex global chat line: {}", failure.javaClass.simpleName)
                ProxyGlobalChatSinkReceipt("unknown", reason = "minecraft-render-failed")
            }

        val config = configProvider()
        val discordMessage = format(config, "discord.chat-pattern", DEFAULT_EXTERNAL_PATTERN, content)
        val telegramMessage = "${ProxyGlobalChatService.CODEX_AUTHOR} » $content"
        val discord =
            try {
                if (discordSink(discordMessage)) {
                    ProxyGlobalChatSinkReceipt("accepted")
                } else {
                    ProxyGlobalChatSinkReceipt("unavailable", reason = "discord-chat-unavailable")
                }
            } catch (_: Throwable) {
                ProxyGlobalChatSinkReceipt("unknown", reason = "discord-send-outcome-unknown")
            }
        val telegram =
            try {
                telegramSink(telegramMessage).handle { telegramReceipt, failure ->
                    when {
                        failure != null -> ProxyGlobalChatSinkReceipt("unknown", reason = "telegram-send-outcome-unknown")
                        telegramReceipt == null -> ProxyGlobalChatSinkReceipt("unavailable", reason = "telegram-chat-unavailable")
                        else ->
                            ProxyGlobalChatSinkReceipt(
                                status = "confirmed",
                                messageId = telegramReceipt.messageId,
                                threadId = telegramReceipt.threadId,
                                chatId = telegramReceipt.chatId,
                            )
                    }
                }
            } catch (_: Throwable) {
                CompletableFuture.completedFuture(
                    ProxyGlobalChatSinkReceipt("unknown", reason = "telegram-send-outcome-unknown"),
                )
            }

        return minecraft.thenCombine(telegram) { minecraftReceipt, telegramReceipt ->
            ProxyGlobalChatDeliveryResult(minecraftReceipt, discord, telegramReceipt)
        }
    }

    private fun format(
        config: Config,
        key: String,
        fallback: String,
        content: String,
    ): String =
        config.string(key, fallback)
            .replace("%player_name%", ProxyGlobalChatService.CODEX_AUTHOR)
            .replace("%message%", content)

    private companion object {
        val WHITE: TextColor = TextColor.color(0xFFFFFF)
        val DARK_GRAY: TextColor = TextColor.color(0x555555)
        val MESSAGE_BEIGE: TextColor = TextColor.color(0xE8D7B7)
        const val DEFAULT_EXTERNAL_PATTERN = "**%player_name%** » %message%"
        val log = LoggerFactory.getLogger(VelocityProxyGlobalChatDelivery::class.java)
    }
}

object ProxyGlobalChat {
    val service = ProxyGlobalChatService()
    val delivery: ProxyGlobalChatDelivery = VelocityProxyGlobalChatDelivery()
}
