package ru.arc.ai;

import java.util.Collection;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ChatHistory {

    final UUID playerUuid;
    final int maxLength;
    Deque<Entry> deque = new ConcurrentLinkedDeque<>();

    public void addPlayerMessage(String message) {
        deque.add(new Entry(message, true, System.currentTimeMillis()));
    }

    public void addBotMessage(String message) {
        deque.add(new Entry(message, true, System.currentTimeMillis()));
    }

    public Collection<Entry> entries() {
        return deque;
    }

    public void clear() {
        deque.clear();
    }

    public record Entry(String text, boolean isPlayer, long timestamp) {
    }

    public void clean(long olderThan) {
        while (!deque.isEmpty() && (deque.peek().timestamp < olderThan || deque.size() > maxLength)) {
            deque.poll();
        }
    }

}
