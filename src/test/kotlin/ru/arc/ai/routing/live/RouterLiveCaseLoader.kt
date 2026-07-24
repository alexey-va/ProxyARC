package ru.arc.ai.routing.live

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ru.arc.ai.routing.context.RouterContext
import ru.arc.ai.routing.history.RouteRecord
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.tickets.IssueTicket

data class RouterLiveCase(
    val id: String,
    val input: RouterLiveCaseInput,
    val expected: String,
)

data class RouterLiveCaseInput(
    val player: String,
    val message: String,
    val raw_text: String? = null,
    val server: String? = null,
    val source: String = "game",
    val directed_at_bot: Boolean = false,
    val reply_to_bot: Boolean = false,
    val continuation_with_bot: Boolean = false,
    val seconds_since_bot: Int? = null,
    val reply_to_player: String? = null,
    val chat_allowed: Boolean? = null,
    val open_ticket_id: String? = null,
    val open_ticket_status: String = "open",
    val open_ticket_summary: String? = null,
    val open_ticket_server: String? = null,
    val recent_chat: List<String> = emptyList(),
    val recent_open_tickets: List<String> = emptyList(),
    val recent_routes: List<String> = emptyList(),
)

object RouterLiveCaseLoader {
    private val gson = Gson()

    fun load(resourcePath: String = "/router_eval/cases.json"): List<RouterLiveCase> {
        val stream =
            RouterLiveCaseLoader::class.java.getResourceAsStream(resourcePath)
                ?: error("Missing test resource: $resourcePath")
        val type = object : TypeToken<List<RouterLiveCase>>() {}.type
        return gson.fromJson(stream.reader(), type)
    }

    fun toContext(case: RouterLiveCase): RouterContext {
        val input = case.input
        val rawText = input.raw_text ?: input.message
        val source =
            when (input.source.lowercase()) {
                "discord" -> InboundMessage.Source.DISCORD
                else -> InboundMessage.Source.GAME
            }

        val openTicket =
            input.open_ticket_id?.let { id ->
                IssueTicket(
                    ticketId = id,
                    threadId = "test-thread",
                    starterMessageId = null,
                    reporter = input.player,
                    title = input.open_ticket_summary ?: "bug",
                    createdAt = 1L,
                    status = input.open_ticket_status,
                    server = input.open_ticket_server,
                )
            }

        val recentRoutes =
            input.recent_routes.mapIndexed { index, line ->
                val intent =
                    when {
                        line.contains(" bug ", ignoreCase = true) -> RouteIntent.BUG
                        line.contains(" chat ", ignoreCase = true) -> RouteIntent.CHAT
                        else -> RouteIntent.SKIP
                    }
                RouteRecord(
                    timestampMs = index.toLong(),
                    intent = intent,
                    messageSnippet = line,
                    confidence = 0.5,
                )
            }

        return RouterContext(
            message =
                InboundMessage(
                    player = input.player,
                    rawText = rawText,
                    displayText = input.message,
                    timestampMs = System.currentTimeMillis(),
                    server = input.server,
                    source = source,
                ),
            meta =
                InboundMeta(
                    directedAtBot = input.directed_at_bot,
                    replyToBot = input.reply_to_bot,
                    continuationWithBot = input.continuation_with_bot,
                    secondsSinceBot = input.seconds_since_bot,
                    replyToPlayer = input.reply_to_player,
                ),
            recentChat = input.recent_chat,
            openTicket = openTicket,
            recentOpenTickets = emptyList(),
            recentRoutes = recentRoutes,
        )
    }

    fun expectedIntent(case: RouterLiveCase): RouteIntent =
        RouteIntent.fromWire(case.expected) ?: RouteIntent.SKIP
}
