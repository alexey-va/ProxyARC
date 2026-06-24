package ru.arc;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.arc.config.Config;
import ru.arc.config.ConfigManager;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class Antibot {


    private final Config config;
    private final FirstJoinData firstJoinData;

    private final Deque<Long> joinTimestamps = new ArrayDeque<>();
    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();

    public Antibot(Path path, FirstJoinData firstJoinData) {
        this.config = ConfigManager.of(path, "antibot.yml");;
        this.firstJoinData = firstJoinData;
    }

    public synchronized boolean processPlayerJoin(String name, UUID uuid, int currentPlayerCount) {
        long currentTime = System.currentTimeMillis();
        cleanOldEntries(currentTime);

        long firstJoinTime = firstJoinData.getFirstJoinTime(name);
        long newPlayerThresholdMs = config.integer("new_player_threshold_seconds", 60) * 1000L; // 1 min default
        boolean isNewPlayer = (currentTime - firstJoinTime) <= newPlayerThresholdMs;

        log.info("Processing player join: name={}, uuid={}, isNewPlayer={}, currentPlayerCount={}",
                name, uuid, isNewPlayer, currentPlayerCount);

        List<String> bannedSubstrings = config.stringList("banned_substrings", List.of("NeoWare"));
        for (String substring : bannedSubstrings) {
            if (name.toLowerCase().contains(substring.toLowerCase())) {
                log.info("Player {} is being kicked due to banned substring in name", name);
                return false;
            }
        }

        if (!isNewPlayer) {
            log.info("Player {} is not a new player, allowing join", name);
            activePlayers.add(uuid);
            return true;
        }

        joinTimestamps.addLast(currentTime);

        int allowedJoins = config.integer("max_joins_per_window", 6);
        int maxConcurrentPlayers = config.integer("max_concurrent_players", 30);

        if (joinTimestamps.size() > allowedJoins || currentPlayerCount >= maxConcurrentPlayers) {
            log.info("Player {} is being kicked due to too many joins in the window", name);
            return false;
        }

        activePlayers.add(uuid);
        return true;
    }

    public void processPlayerLeave(UUID uuid) {
        activePlayers.remove(uuid);
    }

    private void cleanOldEntries(long currentTime) {
        long joinWindowMs = config.integer("join_window_seconds", 60) * 1000L;
        while (!joinTimestamps.isEmpty() && (currentTime - joinTimestamps.peekFirst()) > joinWindowMs) {
            joinTimestamps.pollFirst();
        }
    }
}
