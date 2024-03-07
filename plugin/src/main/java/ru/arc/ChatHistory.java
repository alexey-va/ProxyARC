package ru.arc;

import lombok.RequiredArgsConstructor;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

public class ChatHistory {

    private final Config config;
    private ConcurrentLinkedDeque<String> history = new ConcurrentLinkedDeque<>();
    int maxHistorySize = 100;

    public ChatHistory(Config config) {
        this.config = config;
        maxHistorySize = config.integer("max-history-size", 100);
    }

    public void add(String player, String message) {
        if (history.size() >= maxHistorySize) {
            history.poll();
        }
        history.add(player+": "+message);
    }

    public String asString() {
        return history.stream()
                .collect(Collectors.joining("\n"));
    }
}
