package ru.arc

import org.slf4j.LoggerFactory
import ru.arc.config.ConfigManager
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Antibot(path: Path, private val firstJoinData: FirstJoinData) {
    private val log = LoggerFactory.getLogger(Antibot::class.java)
    private val config = ConfigManager.of(path, "antibot.yml")
    private val joinTimestamps = ArrayDeque<Long>()
    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()

    @Synchronized
    fun processPlayerJoin(name: String, uuid: UUID, currentPlayerCount: Int): Boolean {
        val currentTime = System.currentTimeMillis()
        cleanOldEntries(currentTime)

        val firstJoinTime = firstJoinData.getFirstJoinTime(name)
        val newPlayerThresholdMs = config.integer("new_player_threshold_seconds", 60) * 1000L
        val isNewPlayer = (currentTime - firstJoinTime) <= newPlayerThresholdMs

        log.info(
            "Processing player join: name={}, uuid={}, isNewPlayer={}, currentPlayerCount={}",
            name,
            uuid,
            isNewPlayer,
            currentPlayerCount,
        )

        val bannedSubstrings = config.stringList("banned_substrings", listOf("NeoWare"))
        for (substring in bannedSubstrings) {
            if (name.lowercase().contains(substring.lowercase())) {
                log.info("Player {} is being kicked due to banned substring in name", name)
                return false
            }
        }

        if (!isNewPlayer) {
            log.info("Player {} is not a new player, allowing join", name)
            activePlayers.add(uuid)
            return true
        }

        joinTimestamps.addLast(currentTime)

        val allowedJoins = config.integer("max_joins_per_window", 6)
        val maxConcurrentPlayers = config.integer("max_concurrent_players", 30)

        if (joinTimestamps.size > allowedJoins || currentPlayerCount >= maxConcurrentPlayers) {
            log.info("Player {} is being kicked due to too many joins in the window", name)
            return false
        }

        activePlayers.add(uuid)
        return true
    }

    fun processPlayerLeave(uuid: UUID) {
        activePlayers.remove(uuid)
    }

    private fun cleanOldEntries(currentTime: Long) {
        val joinWindowMs = config.integer("join_window_seconds", 60) * 1000L
        while (!joinTimestamps.isEmpty() && (currentTime - joinTimestamps.peekFirst()) > joinWindowMs) {
            joinTimestamps.pollFirst()
        }
    }
}
