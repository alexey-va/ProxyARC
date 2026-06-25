package ru.arc.ai.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.google.gson.Gson
import ru.arc.ai.Assistant
import java.util.concurrent.CompletableFuture

object DefaultTools {
    private val gson = Gson()

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
