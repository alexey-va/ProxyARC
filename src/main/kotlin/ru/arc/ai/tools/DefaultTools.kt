package ru.arc.ai.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.google.gson.Gson
import ru.arc.ai.Assistant
import java.util.concurrent.CompletableFuture

object DefaultTools {
    private val gson = Gson()

    @JsonClassDescription("Remember a fact about a player or the server for later replies")
    data class RememberFact(
        @JsonPropertyDescription("Fact text to store")
        @JvmField var fact: String? = null,
        @JsonPropertyDescription("Player nick or topic; defaults to who triggered the bot")
        @JvmField var subject: String? = null,
        @JsonPropertyDescription("Confidence from 0.0 to 1.0")
        @JvmField var confidence: Double? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any {
            val a = assistant ?: return "assistant unavailable"
            val text = fact?.trim().orEmpty()
            if (text.isEmpty()) return "fact is required"
            val subj = subject?.trim()?.takeIf { it.isNotEmpty() } ?: a.lastTriggerPlayer
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
