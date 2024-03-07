package ru.arc;

import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

public class ChatHistory {

    private final Config config;
    private ConcurrentLinkedDeque<ChatMessage> history = new ConcurrentLinkedDeque<>();
    int maxHistorySize = 100;

    public void clear() {
        history.clear();
    }



    record ChatMessage(String player, String message, Set<String> tags) {
    }

    public ChatHistory(Config config) {
        this.config = config;
        maxHistorySize = config.integer("max-history-size", 50);
    }

    public void add(String player, String message, String... tags) {
        if (history.size() >= maxHistorySize) {
            history.poll();
        }
        history.add(new ChatMessage(player, message,
                Arrays.stream(tags)
                        .filter(Objects::nonNull)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet()))
        );
    }


    public String asString(boolean skipJippity) {
        return history.stream()
                .filter(chatMessage -> !skipJippity || !chatMessage.tags.contains("jippity"))
                .map(chatMessage -> chatMessage.player + " написал " + chatMessage.message)
                .collect(Collectors.joining("\n", "\n", "\n"));
    }
}
