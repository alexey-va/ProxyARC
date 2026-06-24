package ru.arc.ai.tools;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import org.jetbrains.annotations.Nullable;
import ru.arc.ai.Assistant;

import java.util.List;

public class DefaultTools {

    @JsonClassDescription("Allows assistant to leave the conversation for a specified duration")
    @Data
    public static class LeaveForTime implements Tool {
        @JsonPropertyDescription("Duration in minutes")
        public Integer durationMinutes;

        @Override
        public Object execute(Assistant assistant) {
            if (assistant != null && durationMinutes != null) assistant.leaveForTime(durationMinutes);
            return "ты ливнул. в следующем сообщении напиши свое финальное сообщение, что ты уходишь. например: 'все, я ливнул, пока чмо' но не один в один";
        }
    }

    @JsonClassDescription("Get top balance players")
    @Data
    public static class GetBalTop implements Tool {
        @JsonPropertyDescription("list of exact player names that must be included in the top")
        public List<String> mustIncludePlayers;

        @Override
        public Object execute(@Nullable Assistant assistant) {
            return ToolsMessager.instance.sendToolMessage(this, true).join();
        }
    }

    @JsonClassDescription("Get information about players")
    @Data
    public static class GetPlayerInfo implements Tool {
        @JsonPropertyDescription("List of exact player names")
        List<String> playerNames;

        @Override
        public Object execute(@Nullable Assistant assistant) {
            return ToolsMessager.instance.sendToolMessage(this, true).join();
        }
    }

}
