package ru.arc.ai.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.google.gson.Gson
import ru.arc.ai.Assistant
import ru.arc.ai.AssistantChatFormat
import ru.arc.ai.IssueTicketContext
import ru.arc.ai.PlayerMessaging
import ru.arc.ai.routing.RoutingModule
import ru.arc.ai.routing.survey.BugSurveySessionStore
import ru.arc.ai.tickets.BugTicketDialogContext
import ru.arc.ai.tickets.IssueTicketFormat
import ru.arc.ai.tickets.IssueTicketStore
import ru.arc.ai.tickets.IssueTicketTitles
import ru.arc.ai.tickets.PlayerWorldNames
import ru.arc.ai.tickets.TicketDialogStore
import ru.arc.config.ProxyConfigs
import ru.arc.velocity.Velocity
import java.util.concurrent.CompletableFuture

object DefaultTools {
    private val gson = Gson()

    @JsonClassDescription("Remember a fact about a player or the server for later replies")
    data class RememberFact(
        @JsonPropertyDescription("Fact text to store")
        @JvmField var fact: String? = null,
        @JsonPropertyDescription("Player nick, or server/общее for server-wide facts; omit for whoever triggered the bot")
        @JvmField var subject: String? = null,
        @JsonPropertyDescription("Confidence from 0.0 to 1.0")
        @JvmField var confidence: Double? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any {
            val a = assistant ?: return "assistant unavailable"
            val text = fact?.trim().orEmpty()
            if (text.isEmpty()) return "fact is required"
            val subj = resolveSubject(subject, a.lastTriggerPlayer)
            val conf = (confidence ?: 0.75).coerceIn(0.0, 1.0)
            val saved = a.memoryStore.remember(subj, text, conf, source = "tool")
            return mapOf(
                "status" to "remembered",
                "id" to saved.id,
                "subject" to saved.subject,
                "fact" to saved.fact,
                "confidence" to saved.confidence,
                "rememberedAt" to saved.rememberedAt,
            )
        }
    }

    /** null subject in storage = general/server fact; non-null = player-specific. */
    internal fun resolveSubject(
        subject: String?,
        lastTriggerPlayer: String?,
    ): String? {
        val raw = subject?.trim()?.takeIf { it.isNotEmpty() }
        return when (raw?.lowercase()) {
            null -> lastTriggerPlayer?.trim()?.takeIf { it.isNotEmpty() }
            "server", "общее", "general", "сервер" -> null
            else -> raw
        }
    }

    @JsonClassDescription("Forget stored facts by id, subject, or partial text match")
    data class ForgetFact(
        @JsonPropertyDescription("Exact fact id from rememberfact")
        @JvmField var factId: String? = null,
        @JsonPropertyDescription("Player nick or topic")
        @JvmField var subject: String? = null,
        @JsonPropertyDescription("Substring that must appear in the fact text")
        @JvmField var factContains: String? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any {
            val a = assistant ?: return "assistant unavailable"
            val removed = a.memoryStore.forget(factId, subject, factContains)
            return if (removed > 0) {
                mapOf("status" to "forgotten", "count" to removed)
            } else {
                mapOf("status" to "not_found", "count" to 0)
            }
        }
    }

    @JsonClassDescription("Allows assistant to leave the conversation for a specified duration")
    data class LeaveForTime(
        @JsonPropertyDescription("Duration in minutes")
        @JvmField var durationMinutes: Int? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any {
            val minutes = durationMinutes
            if (assistant != null && minutes != null) {
                assistant.leaveForTime(minutes)
            }
            return "ты ливнул. в следующем сообщении напиши свое финальное сообщение, что ты уходишь. например: 'все, я ливнул, пока чмо' но не один в один"
        }
    }

    @JsonClassDescription("Get top balance players")
    data class GetBalTop(
        @JsonPropertyDescription("list of exact player names that must be included in the top")
        @JvmField var mustIncludePlayers: List<String>? = null,
    ) : Tool, RemoteToolSupport {
        override fun execute(assistant: Assistant?): Any = executeRemote().join()

        override fun executeRemote(): CompletableFuture<Any> =
            invokeRemoteTool(
                ToolNames.GET_BAL_TOP,
                gson.toJsonTree(mapOf("mustIncludePlayers" to mustIncludePlayers)),
            )
    }

    @JsonClassDescription("Get information about players")
    data class GetPlayerInfo(
        @JsonPropertyDescription("List of exact player names")
        @JvmField var playerNames: List<String>? = null,
    ) : Tool, RemoteToolSupport {
        override fun execute(assistant: Assistant?): Any = executeRemote().join()

        override fun executeRemote(): CompletableFuture<Any> {
            val names = playerNames.orEmpty()
            val routing =
                if (names.size == 1) {
                    ToolRouting.ByPlayer(names.first())
                } else {
                    ToolRouting.Broadcast
                }
            return invokeRemoteTool(
                ToolNames.GET_PLAYER_INFO,
                gson.toJsonTree(mapOf("playerNames" to names)),
                routing,
            )
        }
    }

    @JsonClassDescription("Create a Discord issue ticket when players report a real server bug")
    data class CreateIssueTicket(
        @JsonPropertyDescription("Short ticket title, 5-80 chars")
        @JvmField var title: String? = null,
        @JsonPropertyDescription("Detailed description: what broke, steps, expected vs actual")
        @JvmField var description: String? = null,
        @JsonPropertyDescription("Player who reported; default whoever triggered")
        @JvmField var reporter: String? = null,
        @JsonPropertyDescription("Optional server hint; real backend is resolved from Velocity")
        @JvmField var server: String? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any {
            val ticketTitle = title?.trim().orEmpty()
            val ticketDescription = description?.trim().orEmpty()
            if (ticketTitle.isEmpty()) return "title is required"
            if (ticketDescription.isEmpty()) return "description is required"
            val a = assistant ?: return "assistant unavailable"
            val rep = reporter?.trim()?.takeIf { it.isNotEmpty() } ?: a.lastTriggerPlayer ?: "unknown"
            val ctx = IssueTicketContext.build(a, rep, server)
            val bot = Velocity.discordBot
            if (bot == null) return mapOf("status" to "error", "message" to "discord bot unavailable")
            val normalizedTitle =
                IssueTicketFormat.normalizeTitle(
                    ticketTitle,
                    ctx.displayServer,
                )
            val normalizedDescription =
                IssueTicketFormat.buildDescription(ticketDescription, rep)
            val result = bot.createIssueTicket(normalizedTitle, normalizedDescription, ctx).join()
            bindSurveyTicket(rep, ticketTitle, result)
            return result
        }
    }

    private fun bindSurveyTicket(
        reporter: String,
        title: String,
        result: Any,
    ) {
        if (result !is Map<*, *>) return
        val status = result["status"]?.toString()
        if (status != "created") return
        val ticketId = result["ticketId"]?.toString()?.trim().orEmpty()
        if (ticketId.isEmpty()) return
        val boundTitle = result["title"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: title
        BugSurveySessionStore.bindTicket(reporter, ticketId, boundTitle)
    }

    @JsonClassDescription("Send a global in-game message to all online players on the proxy")
    data class SendGlobalMessage(
        @JsonPropertyDescription("Short question or announcement for everyone online")
        @JvmField var message: String? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any {
            val text = message?.trim().orEmpty()
            if (text.isEmpty()) return "message required"
            val result = PlayerMessaging.sendGlobal(text)
            if (result["status"] == "sent") {
                assistant?.lastTriggerPlayer?.trim()?.takeIf { it.isNotEmpty() }?.let { primary ->
                    BugSurveySessionStore.markAwaitingGlobalResponses(primary, text)
                }
                assistant?.recordTicketDialog("скорен (глобал): $text")
                assistant?.observeChat(
                    RoutingModule.formatBotObserveLine(
                        text,
                        AssistantChatFormat.displayName(ProxyConfigs.module("assistant.yml")),
                    ),
                )
            }
            return result
        }
    }

    @JsonClassDescription(
        "Send a private in-game message to a player. Offline players are logged (ok for ops simulate).",
    )
    data class SendPrivateMessage(
        @JsonPropertyDescription("Exact player name (online or offline)")
        @JvmField var playerName: String? = null,
        @JsonPropertyDescription("Message text, short and clear")
        @JvmField var message: String? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any {
            val name = playerName?.trim().orEmpty()
            val text = message?.trim().orEmpty()
            if (name.isEmpty()) return "playerName required"
            if (text.isEmpty()) return "message required"
            val result = PlayerMessaging.sendPrivate(name, text)
            if (PlayerMessaging.isPrivateMessageAccepted(result)) {
                if (result["status"] == "sent") {
                    RoutingModule.recordBotReply(name)
                }
                val suffix =
                    if (result["status"] == "offline") {
                        " [offline, logged]"
                    } else {
                        ""
                    }
                assistant?.recordTicketDialog("скорен (личка → $name)$suffix: $text")
                assistant?.observeChat(
                    RoutingModule.formatBotObserveLine(
                        "личка: $text",
                        AssistantChatFormat.displayName(ProxyConfigs.module("assistant.yml")),
                    ),
                )
            }
            return result
        }
    }

    @JsonClassDescription("Update an existing Discord issue ticket by ID (RB-xxxxx)")
    data class UpdateIssueTicket(
        @JsonPropertyDescription("Ticket id from createissueticket, e.g. RB-00042")
        @JvmField var ticketId: String? = null,
        @JsonPropertyDescription("Text to append to ticket description")
        @JvmField var appendDescription: String? = null,
        @JsonPropertyDescription("New title if needed")
        @JvmField var title: String? = null,
        @JsonPropertyDescription("open or closed")
        @JvmField var status: String? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any {
            val id = ticketId?.trim().orEmpty()
            if (id.isEmpty()) return "ticketId required"
            val bot = Velocity.discordBot
            if (bot == null) return mapOf("status" to "error", "message" to "discord bot unavailable")
            val statusNorm = status?.trim()?.lowercase()
            val stored = IssueTicketStore.find(id)
            val reporter =
                assistant?.lastTriggerPlayer?.trim()?.takeIf { it.isNotEmpty() }
                    ?: stored?.reporter
                    ?: ""
            val enrichedAppend =
                BugTicketDialogContext.enrichAppend(
                    agentText = appendDescription,
                    dialog = TicketDialogStore.snapshot(reporter, 12),
                    triggerMessage = assistant?.lastTriggerMessage,
                    reporter = reporter,
                ).ifBlank { null }
            val titleRaw = title?.trim()?.takeIf { it.isNotEmpty() }
            val storedTitle = stored?.title.orEmpty()
            val worldFromTitle =
                IssueTicketFormat.extractWorldSuffix(
                    storedTitle.removePrefix(IssueTicketTitles.CLOSED_PREFIX).trim(),
                )
            val worldLabel =
                PlayerWorldNames.resolveDisplay(
                    proxyOrHint = stored?.server ?: assistant?.lastTriggerServer,
                    messageText = assistant?.lastTriggerMessage ?: storedTitle,
                    llmServerHint = stored?.server ?: worldFromTitle,
                ).takeIf { it != "неизвестно" }
                    ?: worldFromTitle
                    ?: PlayerWorldNames.displayProxyOrWorld(assistant?.lastTriggerServer)
            val resolvedTitle =
                when {
                    statusNorm == "closed" ->
                        IssueTicketFormat.normalizeTitle(
                            titleRaw ?: stored?.title.orEmpty(),
                            worldLabel,
                            closed = true,
                        )
                    titleRaw != null ->
                        IssueTicketFormat.normalizeTitle(
                            titleRaw,
                            worldLabel,
                        )
                    else -> null
                }
            val result = bot.updateIssueTicket(id, enrichedAppend, resolvedTitle, status).join()
            val closedSuccessfully =
                statusNorm == "closed" &&
                    result is Map<*, *> &&
                    result["status"] == "updated" &&
                    result["ticketStatus"] == "closed"
            if (closedSuccessfully) {
                val reporter =
                    assistant?.lastTriggerPlayer?.trim()?.takeIf { it.isNotEmpty() }
                        ?: IssueTicketStore.find(id)?.reporter
                if (reporter != null) {
                    BugSurveySessionStore.close(reporter, "ticket_closed")
                }
            }
            return result
        }
    }

    @JsonClassDescription("Finish bug-survey session when ticket is complete or message was not a real bug")
    data class CompleteBugSurvey(
        @JsonPropertyDescription("Optional note for server logs")
        @JvmField var note: String? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any {
            val player = assistant?.lastTriggerPlayer?.trim().orEmpty()
            if (player.isEmpty()) return mapOf("status" to "error", "message" to "no trigger player")
            val closed = BugSurveySessionStore.close(player, note?.trim()?.takeIf { it.isNotEmpty() } ?: "complete_tool")
            return if (closed) {
                mapOf("status" to "closed")
            } else {
                mapOf("status" to "no_active_session")
            }
        }
    }

    @JsonClassDescription("List recent issue tickets from forum and local registry")
    data class ListIssueTickets(
        @JsonPropertyDescription("Max tickets to return, default 10")
        @JvmField var limit: Int? = null,
        @JsonPropertyDescription("Filter by reporter nick")
        @JvmField var reporter: String? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any {
            val bot = Velocity.discordBot
            if (bot == null) return mapOf("status" to "error", "message" to "discord bot unavailable")
            val max = (limit ?: 10).coerceIn(1, 50)
            val filter = reporter?.trim()?.takeIf { it.isNotEmpty() }
            return bot.listIssueTickets(max, filter).join()
        }
    }

    @JsonClassDescription("Get online player inventory snapshot")
    data class GetInventory(
        @JsonPropertyDescription("Exact online player name")
        @JvmField var playerName: String? = null,
    ) : Tool, RemoteToolSupport {
        override fun execute(assistant: Assistant?): Any = executeRemote().join()

        override fun executeRemote(): CompletableFuture<Any> {
            val name = playerName ?: return CompletableFuture.completedFuture("playerName required")
            return invokeRemoteTool(
                ToolNames.GET_INVENTORY,
                gson.toJsonTree(mapOf("playerName" to name)),
                ToolRouting.ByPlayer(name),
            )
        }
    }
}
