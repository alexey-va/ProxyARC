package ru.arc.ai.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import ru.arc.ai.Assistant

object DefaultTools {

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
    ) : Tool {
        override fun execute(assistant: Assistant?): Any =
            ToolsMessager.instance!!.sendToolMessage(this, true).join()
    }

    @JsonClassDescription("Get information about players")
    data class GetPlayerInfo(
        @JsonPropertyDescription("List of exact player names")
        @JvmField var playerNames: List<String>? = null,
    ) : Tool {
        override fun execute(assistant: Assistant?): Any =
            ToolsMessager.instance!!.sendToolMessage(this, true).join()
    }
}
