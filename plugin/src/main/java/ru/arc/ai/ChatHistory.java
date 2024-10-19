package ru.arc.ai;

import ru.arc.CommonCore;
import ru.arc.config.Config;
import ru.arc.config.ConfigManager;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

public class ChatHistory {

    private final Config config = ConfigManager.of(CommonCore.folder, "config.yml");
    private final ConcurrentLinkedDeque<ChatMessage> history = new ConcurrentLinkedDeque<>();
    int maxHistorySize = 100;

    record ChatMessage(String player, String message, Set<String> tags, long timestamp) {
    }

    public void clear() {
        history.clear();
    }

    public String forJippity(long millis) {
        return history.stream()
                .filter(chatMessage -> !chatMessage.tags.contains("jippity"))
                .filter(cm -> System.currentTimeMillis() - cm.timestamp < millis)
                .map(chatMessage -> chatMessage.player + " написал " + chatMessage.message)
                .collect(Collectors.joining("\n", "\n", "\n"));
    }


    public ChatHistory() {
        maxHistorySize = config.integer("max-history-size", 50);
    }

    public void add(String player, String message, String... tags) {
        if (history.size() >= maxHistorySize) {
            history.poll();
        }
        history.add(new ChatMessage(player, message, Arrays.stream(tags)
                        .filter(Objects::nonNull)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet()),
                        System.currentTimeMillis()
                )
        );
    }

}
