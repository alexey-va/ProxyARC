package ru.arc.discord

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import org.slf4j.LoggerFactory
import ru.arc.core.modules.LoggingModule
import ru.arc.core.modules.TransportFailureLogFilter
import ru.arc.Utils
import ru.arc.auction.AuctionItemDto
import ru.arc.config.Config
import ru.arc.velocity.Velocity
import ru.arc.transport.TransportOutageState
import java.awt.Color
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class DiscordFeedService(
    private val session: DiscordSession,
    private val config: Config,
    private val joinConfig: Config,
    private val executor: ScheduledExecutorService,
    private val statusChannelId: String? = null,
) : AutoCloseable {
    @Volatile
    private var lastPublishedSignature: String? = null
    @Volatile
    private var lastSuccessfulPublishAtMs: Long = 0
    @Volatile
    private var playerListRateLimitUntilMs: Long = 0
    private val statusPublishGate = DiscordStatusPublishGate<DiscordNetworkSnapshot>()
    private val retryGeneration = AtomicInteger(0)
    @Volatile
    private var retryFuture: ScheduledFuture<*>? = null
    private val auctionPublishGate = DiscordStatusPublishGate<List<AuctionItemDto>>()
    private val auctionTransportOutage = TransportOutageState()
    private val auctionPermanentFailure = TransportOutageState()
    private val auctionRetryLock = Any()
    @Volatile
    private var auctionRetryFuture: ScheduledFuture<*>? = null
    private var auctionRetryAttempt = 0
    private var latestAuctionItems: List<AuctionItemDto>? = null
    @Volatile
    private var closed = false
    @Volatile
    private var auctionMessage: Pair<String, String>? = null

    fun updateAuctionItems(items: List<AuctionItemDto>) {
        if (closed || session.snapshot()?.channels?.auction == null) return
        val snapshot = items.toList()
        val retryPending = synchronized(auctionRetryLock) {
            if (closed) return
            latestAuctionItems = snapshot
            auctionRetryFuture != null
        }
        if (retryPending) return
        val selected = auctionPublishGate.offer(snapshot) ?: return
        publishAuctionItems(selected)
    }

    private fun publishAuctionItems(items: List<AuctionItemDto>) {
        val snapshot = session.snapshot()
        val channel = snapshot?.channels?.auction
        if (channel == null) {
            auctionPublishGate.abandon()
            return
        }
        runCatching {
            val embed = buildAuctionEmbed(items)
            val cached = auctionMessage?.takeIf { it.first == channel.id }?.second
            val messageId = if (cached != null) CompletableFuture.completedFuture(cached)
            else channel.history.retrievePast(100).submit().thenApply { messages ->
                messages.filter { message ->
                    message.author.id == snapshot.jda.selfUser.id &&
                        message.embeds.any { auctionTitleMatches(it.title) }
                }.minByOrNull { it.idLong }?.id
            }
            messageId.thenCompose { id ->
                if (id == null) channel.sendMessageEmbeds(embed).submit()
                else channel.editMessageEmbedsById(id, embed).submit()
            }.whenComplete { message, error ->
                if (error != null) auctionPublishFailed(error)
                else {
                    auctionMessage = channel.id to message.id
                    auctionPublishRecovered()
                    val pending = auctionPublishGate.complete()
                    if (pending != null) {
                        runCatching { executor.execute { publishAuctionItems(pending) } }
                            .onFailure(::auctionPublishFailed)
                    }
                }
            }
        }.onFailure(::auctionPublishFailed)
    }

    private fun auctionPublishFailed(error: Throwable) {
        // Reconcile history on the next snapshot: a timed-out send may have succeeded.
        auctionMessage = null
        auctionPublishGate.abandon()
        val transient = isDiscordTransportFailure(error)
        logAuctionFailure(error, transient)
        if (transient) scheduleAuctionRetry()
    }

    private fun auctionTitleMatches(title: String?): Boolean {
        if (title == null) return false
        val template = config.string("auction.title", "Предметы на аукционе")
        val pattern = template.split("%amount%").joinToString("\\d+") { Regex.escape(it) }
        return Regex(pattern).matches(title)
    }

    internal fun buildAuctionEmbed(items: List<AuctionItemDto>): MessageEmbed {
        val builder =
            EmbedBuilder()
                .setTitle(
                    config.string("auction.title", "Предметы на аукционе")
                        .replace("%amount%", items.size.toString()),
                ).setColor(Color.GREEN)
        if (items.isEmpty()) {
            builder.setDescription(config.string("auction.empty", "Сейчас на аукционе нет активных лотов."))
        }
        var rowItems = 0
        items.forEachIndexed { index, item ->
            builder.addField(
                "${item.amount} x ${item.display}\u2003\u2003\u2003",
                itemDescription(item),
                true,
            )
            rowItems++
            if (rowItems >= 3 && index < items.lastIndex) {
                builder.addField("\u200B", "\u200B", false)
                rowItems = 0
            }
        }
        return builder.setTimestamp(OffsetDateTime.now()).build()
    }

    fun refreshPlayerListFromProxy() {
        val proxy = Velocity.proxyServer ?: return
        updateNetworkStatus(DiscordNetworkSnapshot.capture(proxy))
    }

    fun updatePlayerList(players: Collection<String>) {
        updateNetworkStatus(
            DiscordNetworkSnapshot(
                players.map { DiscordOnlinePlayer(it, null) },
                emptySet(),
            ),
        )
    }

    fun updateNetworkStatus(snapshot: DiscordNetworkSnapshot) = updateNetworkStatus(snapshot, attempt = 0)

    private fun updateNetworkStatus(
        snapshot: DiscordNetworkSnapshot,
        attempt: Int,
    ) {
        if (playerListChannel() == null) return
        val selected = statusPublishGate.offer(snapshot) ?: return
        processAcquiredNetworkStatus(selected, attempt)
    }

    private fun processAcquiredNetworkStatus(
        snapshot: DiscordNetworkSnapshot,
        attempt: Int,
    ) {
        val now = System.currentTimeMillis()
        val signature = networkSignature(snapshot)
        if (
            now < playerListRateLimitUntilMs ||
            !shouldUpdatePlayerList(signature, lastPublishedSignature, lastSuccessfulPublishAtMs, now)
        ) {
            releaseStatusPublishSlot()
            return
        }
        publishPlayerList(snapshot, signature, attempt)
    }

    fun sendJoinEmbed(
        playerName: String,
        joinType: DiscordBot.JoinType,
        override: String?,
    ) {
        val channel = session.snapshot()?.channels?.join ?: return
        val title = joinTitle(playerName, joinType, override?.let(DiscordMessageCodec::sanitizeMinecraftFormatting))
        val url = joinAuthorUrl(joinConfig.string("discord.url", ""))
        val icon =
            joinConfig.string("discord.icon", "https://cravatar.eu/helmavatar/%player_name%/128.png")
                .replace("%player_name%", playerName)
        channel.sendMessageEmbeds(
            EmbedBuilder()
                .setColor(title.color)
                .setAuthor(title.title, url, icon)
                .setTimestamp(OffsetDateTime.now())
                .build(),
        ).queue({}, { error -> log.warn("Failed to send Discord join message", error) })
    }

    private fun itemDescription(item: AuctionItemDto): String =
        config.string(
            "auction.description",
            "Seller: %seller%\nPrice: %price%\nExpire: %expire%\nCategory: %category%",
        ).replace("%seller%", item.seller ?: "")
            .replace("%price%", item.price ?: "")
            .replace("%expire%", Utils.formatTime(item.expire - System.currentTimeMillis()))
            .replace("%category%", item.category ?: "")

    private fun buildPlayerListEmbed(snapshot: DiscordNetworkSnapshot): MessageEmbed {
        val maxPlayers = config.integer("player-list.max-players", 100)
        val author =
            config.string("player-list.title", "Игроки на сервере (%amount%/%max%)")
                .replace("%amount%", snapshot.online.toString())
                .replace("%max%", maxPlayers.toString())
        val builder = EmbedBuilder()
            .setColor(Color.GREEN)
            .setAuthor(author)
        if (snapshot.players.isEmpty()) {
            builder.setDescription(config.string("player-list.empty", "Сейчас на серверах никого нет."))
        } else {
            val grouped = snapshot.players.groupBy { it.server?.ifBlank { null } ?: "подключение" }.toSortedMap()
            grouped.forEach { (server, players) ->
                builder.addField(
                    config.string("player-list.server-title", "%server% • %amount%")
                        .replace("%server%", server)
                        .replace("%amount%", players.size.toString()),
                    players.joinToString("\n") { it.name }.take(1_024),
                    true,
                )
            }
        }
        return builder.setTimestamp(OffsetDateTime.now()).build()
    }

    private fun publishPlayerList(
        snapshot: DiscordNetworkSnapshot,
        signature: String,
        attempt: Int,
    ) {
        if (attempt == 0) cancelPendingRetry()
        val channel = playerListChannel()
        if (channel == null) {
            releaseStatusPublishSlot()
            return
        }
        val embed = buildPlayerListEmbed(snapshot)
        val latestMessageId = channel.latestMessageId
        val action =
            if (latestMessageId == "0") channel.sendMessageEmbeds(embed)
            else channel.editMessageEmbedsById(latestMessageId, embed)
        action.queue(
            {
                lastPublishedSignature = signature
                lastSuccessfulPublishAtMs = System.currentTimeMillis()
                cancelPendingRetry()
                releaseStatusPublishSlot()
            },
            { error ->
                scheduleRetry(signature, attempt, error)
                statusPublishGate.abandon()
            },
        )
    }

    private fun scheduleRetry(
        signature: String,
        attempt: Int,
        error: Throwable,
    ) {
        if (attempt + 1 >= PLAYER_LIST_MAX_RETRIES) {
            log.warn("Player list Discord update failed after {} attempts", attempt + 1, error)
            return
        }
        val retryAfter = parseRetryAfterMs(error)
        if (retryAfter != null) playerListRateLimitUntilMs = System.currentTimeMillis() + retryAfter
        val delay = retryAfter ?: minOf(30_000L, 2_000L shl attempt)
        val generation = retryGeneration.get()
        retryFuture =
            runCatching {
                executor.schedule(
                    {
                        if (generation != retryGeneration.get()) return@schedule
                        val proxy = Velocity.proxyServer ?: return@schedule
                        val players = DiscordNetworkSnapshot.capture(proxy)
                        val current = networkSignature(players)
                        if (current == signature) {
                            updateNetworkStatus(players, attempt + 1)
                        } else {
                            updateNetworkStatus(players)
                        }
                    },
                    delay,
                    TimeUnit.MILLISECONDS,
                )
            }.getOrNull()
    }

    private fun releaseStatusPublishSlot() {
        val pending = statusPublishGate.complete()
        if (pending == null) return
        runCatching { executor.execute { processAcquiredNetworkStatus(pending, attempt = 0) } }
    }

    private fun cancelPendingRetry() {
        retryGeneration.incrementAndGet()
        retryFuture?.cancel(false)
        retryFuture = null
    }

    private fun joinTitle(
        playerName: String,
        joinType: DiscordBot.JoinType,
        override: String?,
    ): ColoredTitle =
        when (joinType) {
            DiscordBot.JoinType.FIRST_TIME ->
                ColoredTitle(
                    Color.decode(config.string("discord.first-time.color", "#0000ff")),
                    (override ?: config.string("discord.first-time.message", "Игрок %player_name% впервые на сервере!"))
                        .replace("%player_name%", playerName),
                )
            DiscordBot.JoinType.JOIN ->
                ColoredTitle(
                    Color.decode(config.string("discord.join.color", "#00ff00")),
                    (override ?: config.string("discord.join.message", "Игрок %player_name% присоединился к серверу!"))
                        .replace("%player_name%", playerName),
                )
            DiscordBot.JoinType.LEAVE ->
                ColoredTitle(
                    Color.decode(config.string("discord.leave.color", "#ff0000")),
                    (override ?: config.string("discord.leave.message", "Игрок %player_name% покинул сервер!"))
                        .replace("%player_name%", playerName),
                )
        }

    private fun parseRetryAfterMs(error: Throwable): Long? {
        val message = generateSequence(error as Throwable?) { it.cause }.joinToString(" ") { it.message.orEmpty() }
        return Regex("Retry-After:\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.get(1)?.toLongOrNull()
            ?.times(1_000L)?.coerceAtLeast(1_000L)
    }

    private fun scheduleAuctionRetry() {
        synchronized(auctionRetryLock) {
            if (closed || latestAuctionItems == null || auctionRetryFuture != null) return
            auctionRetryAttempt++
            val delay = discordRetryDelayMs(auctionRetryAttempt)
            auctionRetryFuture =
                runCatching {
                    executor.schedule(
                        {
                            val latest =
                                synchronized(auctionRetryLock) {
                                    auctionRetryFuture = null
                                    if (closed) null else latestAuctionItems
                                } ?: return@schedule
                            val selected = auctionPublishGate.offer(latest) ?: return@schedule
                            publishAuctionItems(selected)
                        },
                        delay,
                        TimeUnit.MILLISECONDS,
                    )
                }.onFailure { error ->
                    log.error("Could not schedule Discord auction-list retry", error)
                }.getOrNull()
        }
    }

    private fun logAuctionFailure(
        error: Throwable,
        willRetry: Boolean,
    ) {
        if (willRetry) {
            if (!auctionTransportOutage.failed()) return
            if (LoggingModule.transportFailureWasLogged(TransportFailureLogFilter.JDA_LOGGER_NAME)) return
            log.warn("Failed to publish auction list; latest snapshot will be retried with history reconciliation", error)
        } else {
            // A permanent API/configuration failure remains actionable even if the feed
            // was already coalescing a separate transient transport outage.
            if (!auctionPermanentFailure.failed()) return
            log.warn("Failed to publish auction list; failure is not a transient transport error", error)
        }
    }

    private fun auctionPublishRecovered() {
        synchronized(auctionRetryLock) {
            auctionRetryFuture?.cancel(false)
            auctionRetryFuture = null
            auctionRetryAttempt = 0
        }
        val ownRepeats = auctionTransportOutage.recovered()
        val permanentRepeats = auctionPermanentFailure.recovered()
        val libraryAlreadyLogged =
            LoggingModule.transportFailureWasLogged(TransportFailureLogFilter.JDA_LOGGER_NAME)
        val libraryRepeats = LoggingModule.transportRecovered(TransportFailureLogFilter.JDA_LOGGER_NAME)
        if (ownRepeats != null || libraryAlreadyLogged || permanentRepeats != null) {
            log.info(
                "Discord auction list recovered; repeated failures coalesced={} (feed={}, permanent={}, JDA={})",
                (ownRepeats ?: 0) + (permanentRepeats ?: 0) + libraryRepeats,
                ownRepeats ?: 0,
                permanentRepeats ?: 0,
                libraryRepeats,
            )
        }
    }

    private fun playerListChannel() =
        statusChannelId?.let { session.jda()?.getTextChannelById(it) }
            ?: session.snapshot()?.channels?.playerList

    override fun close() {
        closed = true
        synchronized(auctionRetryLock) {
            auctionRetryFuture?.cancel(false)
            auctionRetryFuture = null
            latestAuctionItems = null
            auctionRetryAttempt = 0
        }
        auctionTransportOutage.reset()
        auctionPermanentFailure.reset()
        auctionPublishGate.abandon()
        statusPublishGate.abandon()
        cancelPendingRetry()
    }

    private data class ColoredTitle(val color: Color, val title: String)

    companion object {
        private val log = LoggerFactory.getLogger(DiscordFeedService::class.java)
        private const val PLAYER_LIST_MAX_RETRIES = 5
        private const val PLAYER_LIST_HEARTBEAT_MS = 10 * 60 * 1_000L

        internal fun playerListSignature(players: Collection<String>): String =
            players.sorted().joinToString("\n")

        internal fun joinAuthorUrl(configured: String): String? = configured.trim().takeIf(String::isNotEmpty)

        internal fun networkSignature(snapshot: DiscordNetworkSnapshot): String =
            snapshot.players
                .sortedWith(compareBy<DiscordOnlinePlayer> { it.server.orEmpty() }.thenBy { it.name.lowercase() })
                .joinToString("\n") { "${it.server.orEmpty()}\u0000${it.name}" }

        internal fun shouldUpdatePlayerList(
            signature: String,
            lastPublished: String?,
            lastSuccessfulAtMs: Long,
            nowMs: Long,
            heartbeatMs: Long = PLAYER_LIST_HEARTBEAT_MS,
        ): Boolean = signature != lastPublished || nowMs - lastSuccessfulAtMs >= heartbeatMs
    }
}

/** Keeps at most one Discord status mutation in flight and coalesces bursts to the latest snapshot. */
internal class DiscordStatusPublishGate<T> {
    private var inFlight = false
    private var pending: T? = null

    @Synchronized
    fun offer(value: T): T? {
        if (inFlight) {
            pending = value
            return null
        }
        inFlight = true
        return value
    }

    @Synchronized
    fun complete(): T? {
        if (!inFlight) return null
        val next = pending
        pending = null
        if (next == null) inFlight = false
        return next
    }

    @Synchronized
    fun abandon() {
        inFlight = false
        pending = null
    }
}
