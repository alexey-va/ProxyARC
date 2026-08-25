package ru.arc.discord

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.slf4j.LoggerFactory
import ru.arc.ai.IssueTicketContext
import ru.arc.ai.tickets.IssueTicket
import ru.arc.ai.tickets.IssueTicketEmbedParser
import ru.arc.ai.tickets.IssueTicketFormat
import ru.arc.ai.tickets.IssueTicketStore
import ru.arc.ai.tickets.IssueTicketTitles
import ru.arc.config.Config
import java.awt.Color
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

internal class DiscordTicketService(
    private val session: DiscordSession,
    private val config: Config,
    private val executor: ExecutorService,
    private val replyNotifier: (reporter: String, ticketId: String, url: String) -> Unit = { _, _, _ -> },
) {
    fun requestForumSync() {
        if (executor.isShutdown) return
        runCatching {
            executor.execute {
                syncForumTickets().whenComplete { _, error ->
                    if (error != null) log.warn("Forum ticket sync failed", error)
                }
            }
        }.onFailure {
            if (!executor.isShutdown) log.warn("Failed to schedule forum ticket sync", it)
        }
    }

    fun syncForumTickets(): CompletableFuture<Int> {
        val forum = session.snapshot()?.channels?.issueTickets as? ForumChannel
            ?: return CompletableFuture.completedFuture(0)
        val threads = forum.threadChannels.sortedByDescending { it.timeCreated }
        val presentIds = threads.mapTo(linkedSetOf()) { it.id }
        if (threads.isEmpty()) {
            logReconciledClosures(IssueTicketStore.reconcileForumThreads(presentIds), "forum empty")
            return CompletableFuture.completedFuture(0)
        }
        val futures =
            threads.map { thread ->
                CompletableFuture<Int>().also { future ->
                    thread.retrieveStartMessage().queue(
                        { message ->
                            val parsed = message.embeds.firstOrNull()?.let { IssueTicketEmbedParser.fromThread(thread, it) }
                            if (parsed == null) {
                                future.complete(0)
                            } else {
                                IssueTicketStore.mergeFromForum(parsed)
                                future.complete(1)
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
            logReconciledClosures(
                IssueTicketStore.reconcileForumThreads(presentIds),
                "thread deleted in Discord",
            )
            if (count != null && count > 0) log.info("Forum ticket sync updated {} threads", count)
        }
    }

    fun createIssueTicket(
        title: String,
        description: String,
        context: IssueTicketContext,
    ): CompletableFuture<Any> {
        val channel = session.snapshot()?.channels?.issueTickets
            ?: return completedError("issue-tickets channel not configured")
        val ticketId = IssueTicketStore.nextTicketId()
        val prefix = config.string("issue-tickets.title-prefix", "")
        val maxTitleLength = if (channel is ForumChannel) ForumChannel.MAX_FORUM_TOPIC_LENGTH else 200
        val fullTitle = (prefix + title).take(maxTitleLength)
        val embedBuilder =
            EmbedBuilder()
                .setTitle(fullTitle)
                .setDescription(description.take(4096))
                .addField("ID", ticketId, true)
                .addField("Репортёр", context.reporter, true)
                .addField("Сервер", context.serverFieldValue(), true)
                .addField("Дата", context.reportedAt, true)
                .addField("Источник", context.source, true)
                .setColor(ticketColor())
                .setTimestamp(OffsetDateTime.now())
        context.triggerMessage?.trim()?.takeIf(String::isNotEmpty)?.let {
            embedBuilder.addField("Триггер", it.take(1024), false)
        }
        context.chatSnippet?.let { embedBuilder.addField("Контекст чата", it.take(1024), false) }
        context.dialogSnippet?.let { embedBuilder.addField("Диалог", it.take(1024), false) }

        val embed = embedBuilder.build()
        val future = CompletableFuture<Any>()
        when (channel) {
            is ForumChannel ->
                channel.createForumPost(fullTitle, MessageCreateData.fromEmbeds(embed)).queue(
                    { post ->
                        persistTicket(
                            ticketId,
                            post.threadChannel.id,
                            post.message.id,
                            context.reporter,
                            fullTitle,
                            description.take(300),
                            context.displayServer,
                        )
                        requestForumSync()
                        future.complete(
                            mapOf(
                                "status" to "created",
                                "ticketId" to ticketId,
                                "threadId" to post.threadChannel.id,
                                "url" to post.threadChannel.jumpUrl,
                                "title" to fullTitle,
                            ),
                        )
                    },
                    { error -> completeTicketFailure(future, "forum post", error) },
                )
            is TextChannel ->
                channel.sendMessageEmbeds(embed).queue(
                    { message ->
                        persistTicket(
                            ticketId,
                            message.channel.id,
                            message.id,
                            context.reporter,
                            fullTitle,
                            description.take(300),
                            context.displayServer,
                        )
                        requestForumSync()
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
                    { error -> completeTicketFailure(future, "message", error) },
                )
            else -> future.complete(mapOf("status" to "error", "message" to "unsupported channel type"))
        }
        return future
    }

    fun updateIssueTicket(
        ticketId: String,
        appendDescription: String?,
        newTitle: String?,
        status: String?,
    ): CompletableFuture<Any> {
        val ticket = IssueTicketStore.find(ticketId)
            ?: return CompletableFuture.completedFuture(mapOf("status" to "not_found", "ticketId" to ticketId))
        val jda = session.jda() ?: return completedError("jda unavailable")
        val thread = jda.getThreadChannelById(ticket.threadId)
            ?: return CompletableFuture.completedFuture(
                mapOf("status" to "error", "message" to "thread not found", "ticketId" to ticket.ticketId),
            )
        val append = appendDescription?.trim()?.takeIf(String::isNotEmpty)
        val titleUpdate = newTitle?.trim()?.takeIf(String::isNotEmpty)
        val statusUpdate = status?.trim()?.lowercase()?.takeIf { it in setOf("open", "closed") }
        if (append == null && titleUpdate == null && statusUpdate == null) return completedError("nothing to update")

        val future = CompletableFuture<Any>()
        thread.retrieveStartMessage().queue(
            { message ->
                val old = message.embeds.firstOrNull()
                if (old == null) {
                    future.complete(mapOf("status" to "error", "message" to "starter message has no embed"))
                    return@queue
                }
                val oldTitle = old.title?.takeIf(String::isNotBlank) ?: ticket.title
                val resolvedTitle =
                    when {
                        titleUpdate != null -> titleUpdate
                        statusUpdate == "closed" -> IssueTicketTitles.markClosed(oldTitle)
                        else -> null
                    }
                val builder =
                    EmbedBuilder()
                        .setTitle((resolvedTitle ?: oldTitle).take(256))
                        .setDescription(old.description)
                        .setColor(old.color ?: ticketColor())
                        .setTimestamp(old.timestamp ?: OffsetDateTime.now())
                old.fields.forEach { field ->
                    val name = field.name ?: return@forEach
                    val value = field.value ?: return@forEach
                    when {
                        name.equals("Диалог", true) -> Unit
                        name.equals("Статус", true) && statusUpdate == "closed" -> Unit
                        else -> builder.addField(name, value, field.isInline)
                    }
                }
                if (append != null) {
                    val previous = old.fields.firstOrNull { it.name.equals("Диалог", true) }?.value
                    builder.addField("Диалог", IssueTicketFormat.mergeDialog(previous, append), false)
                }
                if (statusUpdate == "closed") builder.addField("Статус", "Закрыт", true)
                message.editMessageEmbeds(builder.build()).queue(
                    {
                        val updated =
                            ticket.copy(
                                title = resolvedTitle ?: titleUpdate ?: ticket.title,
                                status = statusUpdate ?: ticket.status,
                                summary = append?.replace("\n", " ")?.take(300) ?: ticket.summary,
                            )
                        IssueTicketStore.save(updated)
                        requestForumSync()
                        val result =
                            mapOf(
                                "status" to "updated",
                                "ticketId" to ticket.ticketId,
                                "threadId" to ticket.threadId,
                                "url" to thread.jumpUrl,
                                "ticketStatus" to updated.status,
                            )
                        runCatching { replyNotifier(ticket.reporter, ticket.ticketId, thread.jumpUrl) }
                            .onFailure { log.debug("Ticket reply notification failed", it) }
                        if (statusUpdate == "closed") {
                            thread.manager.setArchived(true).queue(
                                { future.complete(result) },
                                { error ->
                                    log.warn("Issue ticket archive failed for {}: {}", ticket.ticketId, error.message)
                                    future.complete(result)
                                },
                            )
                        } else {
                            future.complete(result)
                        }
                    },
                    { error -> completeTicketFailure(future, "edit", error) },
                )
            },
            { error -> future.complete(mapOf("status" to "error", "message" to error.message)) },
        )
        return future
    }

    fun listIssueTickets(
        limit: Int,
        reporter: String?,
    ): CompletableFuture<Any> {
        val jda = session.jda()
        val tickets =
            IssueTicketStore.listRecent(limit.coerceIn(1, 50), reporter).map { ticket ->
                val url =
                    jda?.getThreadChannelById(ticket.threadId)?.jumpUrl
                        ?: jda?.getTextChannelById(ticket.threadId)?.let { channel ->
                            ticket.starterMessageId?.let {
                                "https://discord.com/channels/${channel.guild.id}/${ticket.threadId}/$it"
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
        return CompletableFuture.completedFuture(
            mapOf(
                "status" to "ok",
                "count" to tickets.size,
                "tickets" to tickets,
                "forumThreads" to listForumThreadSummaries(limit),
            ),
        )
    }

    private fun listForumThreadSummaries(limit: Int): List<Map<String, String?>> {
        val forum = session.snapshot()?.channels?.issueTickets as? ForumChannel ?: return emptyList()
        return forum.threadChannels.sortedByDescending { it.timeCreated }
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
                ticketId,
                threadId,
                starterMessageId,
                reporter,
                title,
                createdAt,
                status,
                summary?.trim()?.takeIf(String::isNotEmpty),
                server?.trim()?.takeIf(String::isNotEmpty),
            ),
        )
    }

    private fun ticketColor(): Color = Color.decode(config.string("issue-tickets.color", "#ff6600"))

    private fun completedError(message: String): CompletableFuture<Any> =
        CompletableFuture.completedFuture(mapOf("status" to "error", "message" to message))

    private fun completeTicketFailure(
        future: CompletableFuture<Any>,
        operation: String,
        error: Throwable,
    ) {
        log.warn("Issue ticket {} failed: {}", operation, error.message)
        future.complete(mapOf("status" to "error", "message" to error.message))
    }

    private fun logReconciledClosures(
        count: Int,
        reason: String,
    ) {
        if (count > 0) log.info("Forum ticket sync closed {} stale tickets ({})", count, reason)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordTicketService::class.java)
    }
}
