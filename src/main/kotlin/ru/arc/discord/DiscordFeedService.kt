package ru.arc.discord

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import org.slf4j.LoggerFactory
import ru.arc.Utils
import ru.arc.auction.AuctionItemDto
import ru.arc.config.Config
import ru.arc.velocity.Velocity
import java.awt.Color
import java.time.OffsetDateTime
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class DiscordFeedService(
    private val session: DiscordSession,
    private val config: Config,
    private val joinConfig: Config,
    private val executor: ScheduledExecutorService,
) : AutoCloseable {
    @Volatile
    private var lastPublishedSignature: String? = null
    @Volatile
    private var lastSuccessfulPublishAtMs: Long = 0
    @Volatile
    private var playerListRateLimitUntilMs: Long = 0
    private val retryGeneration = AtomicInteger(0)
    @Volatile
    private var retryFuture: ScheduledFuture<*>? = null

    fun updateAuctionItems(items: List<AuctionItemDto>) {
        val channel = session.snapshot()?.channels?.auction ?: return
        val builder =
            EmbedBuilder()
                .setTitle(
                    config.string("auction.title", "Предметы на аукционе")
                        .replace("%amount%", items.size.toString()),
                ).setColor(Color.GREEN)
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
        val embed = builder.setTimestamp(OffsetDateTime.now()).build()
        val latestId = channel.latestMessageId
        if (latestId == "0") {
            channel.sendMessageEmbeds(embed).queue({}, ::logAuctionFailure)
        } else {
            channel.editMessageEmbedsById(latestId, embed).queue(
                {},
                { channel.sendMessageEmbeds(embed).queue({}, ::logAuctionFailure) },
            )
        }
    }

    fun refreshPlayerListFromProxy() {
        updatePlayerList(Velocity.plugin?.onlinePlayerNames() ?: return)
    }

    fun updatePlayerList(players: Collection<String>) {
        if (session.snapshot()?.channels?.playerList == null) return
        val now = System.currentTimeMillis()
        if (now < playerListRateLimitUntilMs) return
        val signature = playerListSignature(players)
        if (!shouldUpdatePlayerList(signature, lastPublishedSignature, lastSuccessfulPublishAtMs, now)) return
        publishPlayerList(players, signature, attempt = 0)
    }

    fun sendJoinEmbed(
        playerName: String,
        joinType: DiscordBot.JoinType,
        override: String?,
    ) {
        val channel = session.snapshot()?.channels?.join ?: return
        val title = joinTitle(playerName, joinType, override?.let(DiscordMessageCodec::sanitizeMinecraftFormatting))
        val url = joinConfig.string("discord.url", "https://rus-crafting.ru")
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

    private fun buildPlayerListEmbed(players: Collection<String>): MessageEmbed {
        val maxPlayers = config.integer("player-list.max-players", 100)
        val author =
            config.string("player-list.title", "Игроки на сервере (%amount%/%max%)")
                .replace("%amount%", players.size.toString())
                .replace("%max%", maxPlayers.toString())
        return EmbedBuilder()
            .setColor(Color.GREEN)
            .setAuthor(author)
            .setDescription(players.sorted().joinToString("\n"))
            .setTimestamp(OffsetDateTime.now())
            .build()
    }

    private fun publishPlayerList(
        players: Collection<String>,
        signature: String,
        attempt: Int,
    ) {
        if (attempt == 0) cancelPendingRetry()
        val channel = session.snapshot()?.channels?.playerList ?: return
        val embed = buildPlayerListEmbed(players)
        val action = runCatching { channel.editMessageEmbedsById(channel.latestMessageId, embed) }
            .getOrElse { channel.sendMessageEmbeds(embed) }
        action.queue(
            {
                lastPublishedSignature = signature
                lastSuccessfulPublishAtMs = System.currentTimeMillis()
                cancelPendingRetry()
            },
            { error -> scheduleRetry(signature, attempt, error) },
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
                        val players = Velocity.plugin?.onlinePlayerNames() ?: return@schedule
                        val current = playerListSignature(players)
                        if (current == signature) {
                            publishPlayerList(players, current, attempt + 1)
                        } else {
                            updatePlayerList(players)
                        }
                    },
                    delay,
                    TimeUnit.MILLISECONDS,
                )
            }.getOrNull()
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

    private fun logAuctionFailure(error: Throwable) {
        log.warn("Failed to publish auction list", error)
    }

    override fun close() {
        cancelPendingRetry()
    }

    private data class ColoredTitle(val color: Color, val title: String)

    companion object {
        private val log = LoggerFactory.getLogger(DiscordFeedService::class.java)
        private const val PLAYER_LIST_MAX_RETRIES = 5
        private const val PLAYER_LIST_HEARTBEAT_MS = 10 * 60 * 1_000L

        internal fun playerListSignature(players: Collection<String>): String =
            players.sorted().joinToString("\n")

        internal fun shouldUpdatePlayerList(
            signature: String,
            lastPublished: String?,
            lastSuccessfulAtMs: Long,
            nowMs: Long,
            heartbeatMs: Long = PLAYER_LIST_HEARTBEAT_MS,
        ): Boolean = signature != lastPublished || nowMs - lastSuccessfulAtMs >= heartbeatMs
    }
}
