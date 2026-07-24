package ru.arc.discord

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.Channel
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.slf4j.LoggerFactory
import ru.arc.velocity.Velocity
import ru.arc.Utils
import ru.arc.Utils.mm
import ru.arc.Utils.plain
import ru.arc.ai.IssueTicketContext
import ru.arc.ai.tickets.ForumTicketSync
import ru.arc.ai.tickets.IssueTicket
import ru.arc.ai.tickets.IssueTicketEmbedParser
import ru.arc.ai.tickets.IssueTicketStore
import ru.arc.ai.tickets.IssueTicketFormat
import ru.arc.ai.tickets.IssueTicketTitles
import ru.arc.auction.AuctionItemDto
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import java.awt.Color
import java.time.OffsetDateTime
import java.util.ArrayDeque
import java.util.Arrays
import java.util.Deque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DiscordBot {

    private val log = LoggerFactory.getLogger(DiscordBot::class.java)

    private val config: Config get() = ProxyConfigs.module("discord.yml")
    private val joinConfig: Config get() = ProxyConfigs.module("join_config.yml")
    private var jda: JDA? = null
    private var joinChannel: TextChannel? = null
    private var playerListChannel: TextChannel? = null
    private var auctionChannel: TextChannel? = null
    private var chatChannel: TextChannel? = null
    private var generalChannel: TextChannel? = null
    private var issueTicketsChannel: Channel? = null
    private val service: ScheduledExecutorService = Executors.newScheduledThreadPool(4)
    private val deleteTasks: MutableMap<String, AtomicBoolean> = ConcurrentHashMap()
    private var discordListener: DiscordListener? = null
    private var isEnabled: Boolean = false
    @Volatile
    private var channelsReady: Boolean = false
    @Volatile
    private var lastPublishedSignature: String? = null
    @Volatile
    private var lastSuccessfulPublishAtMs: Long = 0
    @Volatile
    private var playerListRateLimitUntilMs: Long = 0
    private val playerListRetryGeneration = AtomicInteger(0)
    @Volatile
    private var playerListRetryFuture: ScheduledFuture<*>? = null

    fun isReady(): Boolean = isEnabled && channelsReady

    init {
        try {
            if (config.bool("enabled", false)) {
                val token = config.string("token", "token")
                if (token != "token") {
                    val builder = JDABuilder.createDefault(token)
                    builder.disableCache(CacheFlag.VOICE_STATE, CacheFlag.MEMBER_OVERRIDES)
                    jda = builder
                        .enableIntents(Arrays.asList(*GatewayIntent.values()))
                        .build()
                    service.submit {
                        try {
                            jda!!.awaitReady()
                        } catch (e: InterruptedException) {
                            throw RuntimeException(e)
                        }
                        println("Discord bot is ready!")
                        jda!!.textChannels.forEach { channel ->
                            println("${channel.name} ${channel.id}")
                        }
                        jda!!.guilds.forEach { guild ->
                            println("${guild.name} ${guild.id}")
                        }
                        try {
                            joinChannel = jda!!.getGuildChannelById(config.string("channels.join-messages", "none")) as TextChannel?
                            println("Join: $joinChannel")
                        } catch (e: Exception) {
                            log.error("Join channel not found", e)
                        }
                        try {
                            playerListChannel = jda!!.getGuildChannelById(config.string("channels.player-list", "none")) as TextChannel?
                            println("Player list: $playerListChannel")
                        } catch (e: Exception) {
                            log.error("Player list channel not found", e)
                        }
                        try {
                            auctionChannel = jda!!.getGuildChannelById(config.string("channels.auction", "none")) as TextChannel?
                            println("Auction: $auctionChannel")
                        } catch (e: Exception) {
                            log.error("Auction channel not found", e)
                        }
                        try {
                            chatChannel = jda!!.getGuildChannelById(config.string("channels.chat", "none")) as TextChannel?
                            println("Chat: $chatChannel")
                        } catch (e: Exception) {
                            log.error("Chat channel not found", e)
                        }
                        try {
                            generalChannel = jda!!.getGuildChannelById(config.string("channels.general", "none")) as TextChannel?
                            println("General: $generalChannel")
                        } catch (e: Exception) {
                            log.error("General channel not found", e)
                        }
                        try {
                            val issueId = config.string("channels.issue-tickets", "none")
                            if (issueId != "none") {
                                issueTicketsChannel = jda!!.getGuildChannelById(issueId)
                                println("Issue tickets: $issueTicketsChannel")
                            }
                        } catch (e: Exception) {
                            log.error("Issue tickets channel not found", e)
                        }

                        discordListener = DiscordListener(chatChannel!!, generalChannel!!)
                        jda!!.addEventListener(discordListener)
                        channelsReady = true
                        ForumTicketSync.scheduleIfEnabled()
                    }
                    isEnabled = true
                } else {
                    println("Could not initialize discord bot")
                }
            } else {
                println("Discord bot is disabled in config")
            }
        } catch (e: Exception) {
            log.error("Discord bot initialization failed", e)
        }
        instance = this
    }

    fun scheduler(): ScheduledExecutorService = service

    fun syncForumTickets(): CompletableFuture<Int> {
        if (!isReady()) {
            return CompletableFuture.completedFuture(0)
        }
        val forum = issueTicketsChannel as? ForumChannel
        if (forum == null) {
            return CompletableFuture.completedFuture(0)
        }
        val threads = forum.threadChannels.sortedByDescending { it.timeCreated }
        val presentThreadIds = threads.map { it.id }.toSet()
        if (threads.isEmpty()) {
            val closed = IssueTicketStore.reconcileForumThreads(presentThreadIds)
            if (closed > 0) {
                log.info("Forum ticket sync closed {} stale tickets (forum empty)", closed)
            }
            return CompletableFuture.completedFuture(0)
        }
        val futures =
            threads.map { thread ->
                CompletableFuture<Int>().also { future ->
                    thread.retrieveStartMessage().queue(
                        { message ->
                            val embed = message.embeds.firstOrNull()
                            if (embed == null) {
                                future.complete(0)
                                return@queue
                            }
                            val parsed = IssueTicketEmbedParser.fromThread(thread, embed)
                            if (parsed != null) {
                                IssueTicketStore.mergeFromForum(parsed)
                                future.complete(1)
                            } else {
                                future.complete(0)
                            }
                        },
                        { error ->
                            log.debug("Skip forum thread {}: {}", thread.id, error.message)
                            future.complete(0)
                        },
                    )
                }
            }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
            futures.sumOf { it.getNow(0) }
        }.whenComplete { count, _ ->
            val closed = IssueTicketStore.reconcileForumThreads(presentThreadIds)
            if (closed > 0) {
                log.info("Forum ticket sync closed {} stale tickets (thread deleted in Discord)", closed)
            }
            if (count != null && count > 0) {
                log.info("Forum ticket sync updated {} threads", count)
            }
        }
    }

    fun updateAuctionItems(auctionItemDtos: List<AuctionItemDto>) {
        if (!isEnabled) return

        if (auctionChannel == null) {
            println("Auction channel is null! SKipping")
            return
        }

        val embedBuilder = EmbedBuilder()
        embedBuilder.setTitle(
            config.string("auction.title", "Предметы на аукционе")
                .replace("%amount%", auctionItemDtos.size.toString()),
        )
        embedBuilder.setColor(Color.GREEN)

        var count = 0
        for (i in auctionItemDtos.indices) {
            val item = auctionItemDtos[i]
            embedBuilder.addField(
                "${item.amount} x ${item.display}\u2003\u2003\u2003",
                getItemDescription(item),
                true,
            )
            count++
            if (count >= 3 && i < auctionItemDtos.size - 1) {
                embedBuilder.addField("\u200B", "\u200B", false)
                count = 0
            }
        }

        val embed: MessageEmbed = embedBuilder.setTimestamp(OffsetDateTime.now()).build()
        val messageHistory: List<Message> = auctionChannel!!.history.retrievePast(1).complete()

        val latestId = auctionChannel!!.latestMessageId
        if (messageHistory.isEmpty() || messageHistory[0].id != latestId) {
            auctionChannel!!.sendMessageEmbeds(embed).queue()
        } else {
            auctionChannel!!.editMessageEmbedsById(latestId, embed).queue()
        }
    }

    private fun getItemDescription(item: AuctionItemDto): String =
        config.string(
            "auction.description",
            "Seller: %seller%\nPrice: %price%\nExpire: %expire%\nCategory: %category%",
        )
            .replace("%seller%", item.seller ?: "")
            .replace("%price%", item.price ?: "")
            .replace("%expire%", Utils.formatTime(item.expire - System.currentTimeMillis()))
            .replace("%category%", item.category ?: "")

    fun clearChat(id: String) {
        if (!isEnabled) return
        if (deleteTasks.containsKey(id)) {
            deleteTasks[id]!!.set(false)
        }
        deleteTasks[id] = AtomicBoolean(true)
        ForkJoinPool.commonPool().submit {
            val channel: Channel? = jda!!.getGuildChannelById(id)
            if (channel is TextChannel) {
                val deque: Deque<Message> = ArrayDeque()
                for (message in channel.iterableHistory) {
                    if (!deleteTasks.containsKey(id) || !deleteTasks[id]!!.get()) {
                        println("Interrupted clear chat task")
                        return@submit
                    }
                    deque.add(message)
                    if (deque.size >= 5) {
                        deque.pollFirst().delete().queue()
                        try {
                            Thread.sleep(5000)
                        } catch (e: InterruptedException) {
                            println("Interrupted clear chat task")
                            throw RuntimeException(e)
                        }
                    }
                }
            }
        }
    }

    fun stopClearTask(id: String) {
        if (deleteTasks.containsKey(id)) {
            println("Stopping clear task for $id")
            deleteTasks[id]!!.set(false)
        }
    }

    fun refreshPlayerListFromProxy() {
        val plugin = Velocity.plugin ?: return
        updatePlayerList(plugin.onlinePlayerNames())
    }

    fun updatePlayerList(players: Collection<String>) {
        if (!isEnabled || !channelsReady || playerListChannel == null) return

        val now = System.currentTimeMillis()
        if (now < playerListRateLimitUntilMs) return

        val signature = playerListSignature(players)
        if (!shouldUpdatePlayerList(signature, lastPublishedSignature, lastSuccessfulPublishAtMs, now, PLAYER_LIST_HEARTBEAT_MS)) {
            return
        }

        publishPlayerList(players, signature, attempt = 0)
    }

    private fun buildPlayerListEmbed(players: Collection<String>): MessageEmbed {
        val maxPlayers = config.integer("player-list.max-players", 100)
        val current = players.size
        val author = config.string("player-list.title", "Игроки на сервере (%amount%/%max%)")
            .replace("%amount%", current.toString())
            .replace("%max%", maxPlayers.toString())
        return EmbedBuilder()
            .setColor(Color.GREEN)
            .setAuthor(author)
            .setDescription(players.sorted().joinToString("\n"))
            .setTimestamp(OffsetDateTime.now())
            .build()
    }

    private fun publishPlayerList(players: Collection<String>, signature: String, attempt: Int) {
        if (attempt == 0) {
            cancelPendingPlayerListRetries()
        }
        publishPlayerListEmbed(buildPlayerListEmbed(players), signature, attempt)
    }

    private fun publishPlayerListEmbed(embed: MessageEmbed, signature: String, attempt: Int) {
        val channel = playerListChannel ?: return
        val action = runCatching {
            channel.editMessageEmbedsById(channel.latestMessageId, embed)
        }.getOrElse {
            channel.sendMessageEmbeds(embed)
        }

        action.queue(
            {
                lastPublishedSignature = signature
                lastSuccessfulPublishAtMs = System.currentTimeMillis()
                cancelPendingPlayerListRetries()
            },
            { error -> schedulePlayerListRetry(signature, attempt, error) },
        )
    }

    private fun retryPlayerListPublish(expectedSignature: String, attempt: Int) {
        val players = Velocity.plugin?.onlinePlayerNames() ?: return
        val currentSignature = playerListSignature(players)
        if (currentSignature != expectedSignature) {
            log.debug("Player list retry skipped — roster changed")
            updatePlayerList(players)
            return
        }
        publishPlayerList(players, currentSignature, attempt)
    }

    private fun cancelPendingPlayerListRetries() {
        playerListRetryGeneration.incrementAndGet()
        playerListRetryFuture?.cancel(false)
        playerListRetryFuture = null
    }

    private fun schedulePlayerListRetry(
        signature: String,
        attempt: Int,
        error: Throwable,
    ) {
        if (attempt + 1 >= PLAYER_LIST_MAX_RETRIES) {
            log.warn("Player list Discord update failed after {} attempts", attempt + 1, error)
            return
        }
        recordPlayerListRateLimit(error)
        val delayMs = playerListRetryDelayMs(error, attempt)
        log.debug("Player list update retry in {}ms (attempt {})", delayMs, attempt + 2)
        val generation = playerListRetryGeneration.get()
        playerListRetryFuture = service.schedule(
            {
                if (generation != playerListRetryGeneration.get()) return@schedule
                retryPlayerListPublish(signature, attempt + 1)
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun recordPlayerListRateLimit(error: Throwable) {
        parseRetryAfterMs(error)?.let { delayMs ->
            playerListRateLimitUntilMs = System.currentTimeMillis() + delayMs
        }
    }

    private fun playerListRetryDelayMs(error: Throwable, attempt: Int): Long =
        parseRetryAfterMs(error) ?: minOf(30_000L, 2_000L shl attempt)

    private fun parseRetryAfterMs(error: Throwable): Long? {
        val message = generateSequence(error as Throwable?) { it.cause }.joinToString(" ") { it.message.orEmpty() }
        return Regex("Retry-After:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.toLongOrNull()
            ?.times(1_000L)
            ?.coerceAtLeast(1_000L)
    }

    fun sendChatMessage(message: String) {
        if (!isEnabled) return
        if (chatChannel == null) {
            println("Chat channel is null! Skipping")
            return
        }
        chatChannel!!.sendMessage(message).queue()
    }

    fun sendJoinEmbed(playerName: String, joinType: JoinType, override: String?) {
        if (!isEnabled) return
        if (joinChannel == null) {
            println("Join channel is null! Skipping")
            return
        }
        var messageOverride = override
        if (messageOverride != null) {
            messageOverride = plain(mm(messageOverride))
        }
        val coloredTitle = getTitle(playerName, joinType, messageOverride)
        val url = joinConfig.string("discord.url", "https://rus-crafting.ru")
        val icon = joinConfig.string("discord.icon", "https://cravatar.eu/helmavatar/%player_name%/128.png")
            .replace("%player_name%", playerName)
        val embed: MessageEmbed = EmbedBuilder()
            .setColor(coloredTitle.color)
            .setAuthor(coloredTitle.title, url, icon)
            .setTimestamp(OffsetDateTime.now())
            .build()
        joinChannel!!.sendMessageEmbeds(embed).queue()
    }

    fun sendGeneralMessage(message: String) {
        if (!isEnabled) return
        if (generalChannel == null) {
            println("General channel is null! Skipping")
            return
        }
        generalChannel!!.sendMessage(message).queue()
    }

    fun createIssueTicket(
        title: String,
        description: String,
        context: IssueTicketContext,
    ): CompletableFuture<Any> {
        if (!isEnabled || !channelsReady) {
            return CompletableFuture.completedFuture(
                mapOf("status" to "error", "message" to "discord not ready"),
            )
        }
        val channel = issueTicketsChannel
        if (channel == null) {
            return CompletableFuture.completedFuture(
                mapOf("status" to "error", "message" to "issue-tickets channel not configured"),
            )
        }

        val ticketId = IssueTicketStore.nextTicketId()
        val prefix = config.string("issue-tickets.title-prefix", "")
        val maxTitleLength =
            if (channel is ForumChannel) {
                ForumChannel.MAX_FORUM_TOPIC_LENGTH
            } else {
                200
            }
        val fullTitle = (prefix + title).take(maxTitleLength)
        val color = Color.decode(config.string("issue-tickets.color", "#ff6600"))
        val embedBuilder =
            EmbedBuilder()
                .setTitle(fullTitle)
                .setDescription(description.take(4096))
                .addField("ID", ticketId, true)
                .addField("Репортёр", context.reporter, true)
                .addField("Сервер", context.serverFieldValue(), true)
                .addField("Дата", context.reportedAt, true)
                .addField("Источник", context.source, true)
                .setColor(color)
                .setTimestamp(OffsetDateTime.now())

        context.triggerMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { trigger ->
            embedBuilder.addField("Триггер", trigger.take(1024), false)
        }
        context.chatSnippet?.let { snippet ->
            embedBuilder.addField("Контекст чата", snippet.take(1024), false)
        }
        context.dialogSnippet?.let { dialog ->
            embedBuilder.addField("Диалог", dialog.take(1024), false)
        }

        val embed = embedBuilder.build()
        val messageData = MessageCreateData.fromEmbeds(embed)
        val future = CompletableFuture<Any>()

        when (channel) {
            is ForumChannel -> {
                channel.createForumPost(fullTitle, messageData).queue(
                    { post ->
                        val thread = post.threadChannel
                        val starterId = post.message.id
                        persistTicket(
                            ticketId = ticketId,
                            threadId = thread.id,
                            starterMessageId = starterId,
                            reporter = context.reporter,
                            title = fullTitle,
                            summary = description.take(300),
                            server = context.displayServer,
                        )
                        service.submit { syncForumTickets() }
                        future.complete(
                            mapOf(
                                "status" to "created",
                                "ticketId" to ticketId,
                                "threadId" to thread.id,
                                "url" to thread.jumpUrl,
                                "title" to fullTitle,
                            ),
                        )
                    },
                    { error ->
                        log.warn("Issue ticket forum post failed: {}", error.message)
                        future.complete(mapOf("status" to "error", "message" to error.message))
                    },
                )
            }
            is TextChannel -> {
                channel.sendMessageEmbeds(embed).queue(
                    { message ->
                        persistTicket(
                            ticketId = ticketId,
                            threadId = message.channel.id,
                            starterMessageId = message.id,
                            reporter = context.reporter,
                            title = fullTitle,
                            summary = description.take(300),
                            server = context.displayServer,
                        )
                        service.submit { syncForumTickets() }
                        future.complete(
                            mapOf(
                                "status" to "created",
                                "ticketId" to ticketId,
                                "messageId" to message.id,
                                "url" to message.jumpUrl,
                                "title" to fullTitle,
                            ),
                        )
                    },
                    { error ->
                        log.warn("Issue ticket message failed: {}", error.message)
                        future.complete(mapOf("status" to "error", "message" to error.message))
                    },
                )
            }
            else -> {
                future.complete(
                    mapOf("status" to "error", "message" to "channel type not supported: ${channel.type}"),
                )
            }
        }
        return future
    }

    fun updateIssueTicket(
        ticketId: String,
        appendDescription: String?,
        newTitle: String?,
        status: String?,
    ): CompletableFuture<Any> {
        if (!isEnabled || !channelsReady) {
            return CompletableFuture.completedFuture(
                mapOf("status" to "error", "message" to "discord not ready"),
            )
        }
        val ticket = IssueTicketStore.find(ticketId)
        if (ticket == null) {
            return CompletableFuture.completedFuture(
                mapOf("status" to "not_found", "ticketId" to ticketId),
            )
        }
        val jdaInstance = jda
        if (jdaInstance == null) {
            return CompletableFuture.completedFuture(
                mapOf("status" to "error", "message" to "jda unavailable"),
            )
        }
        val thread = jdaInstance.getThreadChannelById(ticket.threadId)
        if (thread == null) {
            return CompletableFuture.completedFuture(
                mapOf("status" to "error", "message" to "thread not found", "ticketId" to ticket.ticketId),
            )
        }

        val append = appendDescription?.trim()?.takeIf { it.isNotEmpty() }
        val titleUpdate = newTitle?.trim()?.takeIf { it.isNotEmpty() }
        val statusUpdate = status?.trim()?.lowercase()?.takeIf { it in setOf("open", "closed") }

        if (append == null && titleUpdate == null && statusUpdate == null) {
            return CompletableFuture.completedFuture(
                mapOf("status" to "error", "message" to "nothing to update"),
            )
        }

        val future = CompletableFuture<Any>()
        thread.retrieveStartMessage().queue(
            { message ->
                val oldEmbed = message.embeds.firstOrNull()
                if (oldEmbed == null) {
                    future.complete(mapOf("status" to "error", "message" to "starter message has no embed"))
                    return@queue
                }
                val embedTitle = oldEmbed.title?.takeIf { it.isNotBlank() } ?: ticket.title
                val resolvedTitle =
                    when {
                        titleUpdate != null -> titleUpdate
                        statusUpdate == "closed" -> IssueTicketTitles.markClosed(embedTitle)
                        else -> null
                    }
                val builder =
                    EmbedBuilder()
                        .setTitle((resolvedTitle ?: embedTitle).take(256))
                        .setDescription(oldEmbed.description)
                        .setColor(oldEmbed.color ?: Color.decode(config.string("issue-tickets.color", "#ff6600")))
                        .setTimestamp(oldEmbed.timestamp ?: OffsetDateTime.now())
                for (field in oldEmbed.fields) {
                    val fieldName = field.name ?: continue
                    val fieldValue = field.value ?: continue
                    when {
                        fieldName.equals("Диалог", ignoreCase = true) -> continue
                        fieldName.equals("Статус", ignoreCase = true) && statusUpdate == "closed" -> continue
                        else -> builder.addField(fieldName, fieldValue, field.isInline)
                    }
                }
                if (append != null) {
                    val existingDialog =
                        oldEmbed.fields
                            .firstOrNull { it.name.equals("Диалог", ignoreCase = true) }
                            ?.value
                    val mergedDialog = IssueTicketFormat.mergeDialog(existingDialog, append)
                    builder.addField("Диалог", mergedDialog, false)
                }
                if (statusUpdate == "closed") {
                    builder.addField("Статус", "Закрыт", true)
                }
                message.editMessageEmbeds(builder.build()).queue(
                    { _ ->
                        val updatedTitle = resolvedTitle ?: titleUpdate ?: ticket.title
                        val updatedStatus = statusUpdate ?: ticket.status
                        val updatedSummary =
                            if (append != null) {
                                append.replace("\n", " ").take(300)
                            } else {
                                ticket.summary
                            }
                        val updated =
                            ticket.copy(
                                title = updatedTitle,
                                status = updatedStatus,
                                summary = updatedSummary ?: ticket.summary,
                            )
                        IssueTicketStore.save(updated)
                        service.submit { syncForumTickets() }
                        val result =
                            mapOf(
                                "status" to "updated",
                                "ticketId" to ticket.ticketId,
                                "threadId" to ticket.threadId,
                                "url" to thread.jumpUrl,
                                "ticketStatus" to updated.status,
                            )
                        if (statusUpdate == "closed") {
                            thread.manager.setArchived(true).queue(
                                { future.complete(result) },
                                { error ->
                                    log.warn(
                                        "Issue ticket archive failed for {}: {}",
                                        ticket.ticketId,
                                        error.message,
                                    )
                                    future.complete(result)
                                },
                            )
                        } else {
                            future.complete(result)
                        }
                    },
                    { error ->
                        log.warn("Issue ticket edit failed for {}: {}", ticket.ticketId, error.message)
                        future.complete(mapOf("status" to "error", "message" to error.message))
                    },
                )
            },
            { error ->
                future.complete(mapOf("status" to "error", "message" to error.message))
            },
        )
        return future
    }

    fun listIssueTickets(
        limit: Int,
        reporter: String?,
    ): CompletableFuture<Any> {
        val tickets =
            IssueTicketStore.listRecent(limit.coerceIn(1, 50), reporter).map { ticket ->
                val url =
                    jda?.getThreadChannelById(ticket.threadId)?.jumpUrl
                        ?: jda?.getTextChannelById(ticket.threadId)?.let { ch ->
                            if (ticket.starterMessageId != null) {
                                "https://discord.com/channels/${ch.guild.id}/$ticket.threadId/$ticket.starterMessageId"
                            } else {
                                null
                            }
                        }
                mapOf(
                    "ticketId" to ticket.ticketId,
                    "title" to ticket.title,
                    "reporter" to ticket.reporter,
                    "status" to ticket.status,
                    "createdAt" to ticket.createdAt,
                    "threadId" to ticket.threadId,
                    "summary" to ticket.summary,
                    "server" to ticket.server,
                    "url" to url,
                )
            }
        val forumThreads = listForumThreadSummaries(limit)
        return CompletableFuture.completedFuture(
            mapOf(
                "status" to "ok",
                "count" to tickets.size,
                "tickets" to tickets,
                "forumThreads" to forumThreads,
            ),
        )
    }

    private fun listForumThreadSummaries(limit: Int): List<Map<String, String?>> {
        val channel = issueTicketsChannel
        if (channel !is ForumChannel) return emptyList()
        return channel.threadChannels
            .sortedByDescending { it.timeCreated }
            .take(limit.coerceIn(1, 50))
            .map { thread ->
                mapOf(
                    "threadId" to thread.id,
                    "name" to thread.name,
                    "url" to thread.jumpUrl,
                    "archived" to thread.isArchived.toString(),
                )
            }
    }

    private fun persistTicket(
        ticketId: String,
        threadId: String,
        starterMessageId: String?,
        reporter: String,
        title: String,
        summary: String? = null,
        server: String? = null,
        status: String = IssueTicket.STATUS_OPEN,
        createdAt: Long = System.currentTimeMillis(),
    ) {
        IssueTicketStore.save(
            IssueTicket(
                ticketId = ticketId,
                threadId = threadId,
                starterMessageId = starterMessageId,
                reporter = reporter,
                title = title,
                createdAt = createdAt,
                status = status,
                summary = summary?.trim()?.takeIf { it.isNotEmpty() },
                server = server?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    data class ColoredTitle(val color: Color, val title: String)

    enum class JoinType {
        FIRST_TIME,
        JOIN,
        LEAVE,
    }

    private fun getTitle(playerName: String, joinType: JoinType, override: String?): ColoredTitle {
        var title = ""
        var color = Color.GRAY

        when (joinType) {
            JoinType.FIRST_TIME -> {
                val text = override ?: config.string("discord.first-time.message", "Игрок %player_name% впервые на сервере!")
                color = Color.decode(config.string("discord.first-time.color", "#0000ff"))
                title = text.replace("%player_name%", playerName)
            }
            JoinType.JOIN -> {
                val text = override ?: config.string("discord.join.message", "Игрок %player_name% присоединился к серверу!")
                color = Color.decode(config.string("discord.join.color", "#00ff00"))
                title = text.replace("%player_name%", playerName)
            }
            JoinType.LEAVE -> {
                val text = override ?: config.string("discord.leave.message", "Игрок %player_name% покинул сервер!")
                color = Color.decode(config.string("discord.leave.color", "#ff0000"))
                title = text.replace("%player_name%", playerName)
            }
        }
        return ColoredTitle(color, title)
    }

    companion object {
        private const val PLAYER_LIST_MAX_RETRIES = 5
        private const val PLAYER_LIST_HEARTBEAT_MS = 10 * 60 * 1000L

        @JvmField
        var instance: DiscordBot? = null

        internal fun playerListSignature(players: Collection<String>): String =
            players.sorted().joinToString("\n")

        internal fun shouldUpdatePlayerList(
            signature: String,
            lastPublished: String?,
            lastSuccessfulAtMs: Long,
            nowMs: Long,
            heartbeatMs: Long = PLAYER_LIST_HEARTBEAT_MS,
        ): Boolean {
            if (signature != lastPublished) return true
            if (lastPublished == null) return true
            return nowMs - lastSuccessfulAtMs >= heartbeatMs
        }
    }
}
