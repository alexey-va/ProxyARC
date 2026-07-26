package ru.arc

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ru.arc.velocity.Velocity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class FirstJoinData(
    private val dbPath: Path = checkNotNull(Velocity.dataFolder).resolve("first_time_join.json"),
) {
    var map: MutableMap<String, Long> = ConcurrentHashMap()
        private set

    fun firstTimeJoin(name: String): Boolean = !map.containsKey(normalize(name))

    fun markAsJoined(name: String) {
        map.putIfAbsent(normalize(name), System.currentTimeMillis())
    }

    fun getFirstJoinTime(name: String): Long =
        requireNotNull(
            map.compute(normalize(name)) { _, value -> value ?: System.currentTimeMillis() },
        ) { "First join timestamp computation returned null" }

    @Synchronized
    fun load() {
        if (!Files.exists(dbPath)) {
            Files.createDirectories(dbPath.parent)
            map = ConcurrentHashMap()
            return
        }

        val gson = Gson()
        val mapType = object : TypeToken<Map<String, Long>>() {}.type
        val loadedMap: Map<String, Long>? =
            Files.newBufferedReader(dbPath).use { reader ->
                gson.fromJson(reader, mapType)
            }
        val normalized = ConcurrentHashMap<String, Long>()
        loadedMap.orEmpty().forEach { (name, joinedAt) ->
            normalized.merge(normalize(name), joinedAt, ::minOf)
        }
        map = normalized
    }

    @Synchronized
    fun save() {
        Files.createDirectories(dbPath.parent)

        val gson = Gson()
        val tempFile = Files.createTempFile(dbPath.parent, "${dbPath.fileName}.", ".tmp")
        try {
            Files.newBufferedWriter(tempFile).use { writer ->
                gson.toJson(map.toMap(), writer)
            }
            try {
                Files.move(
                    tempFile,
                    dbPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tempFile, dbPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun normalize(name: String): String = name.trim().lowercase(Locale.ROOT)
}
