package ru.arc.ai.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.google.gson.Gson
import ru.arc.ai.Assistant
import ru.arc.ai.IssueTicketContext
import ru.arc.ai.PlayerMessaging
import ru.arc.ai.routing.RoutingModule
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
            return bot.createIssueTicket(ticketTitle, ticketDescription, ctx).join()
        }
    }

    @JsonClassDescription("Send a private in-game message to an online player")
    data class SendPrivateMessage(
        @JsonPropertyDescription("Exact online player name")
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
            if (result["status"] == "sent") {
                RoutingModule.recordBotReply(name)
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
            return bot.updateIssueTicket(id, appendDescription, title, status).join()
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
