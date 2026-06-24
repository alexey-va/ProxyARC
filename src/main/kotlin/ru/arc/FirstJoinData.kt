package ru.arc

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class FirstJoinData {
    private val dbPath: Path = CommonCore.folder!!.resolve("first_time_join.json")
    lateinit var map: MutableMap<String, Long>

    fun firstTimeJoin(name: String): Boolean = !map.containsKey(name)

    fun markAsJoined(name: String) {
        map.putIfAbsent(name, System.currentTimeMillis())
    }

    fun getFirstJoinTime(name: String): Long =
        map.compute(name) { _, value -> value ?: System.currentTimeMillis() }!!

    fun load() {
        if (!Files.exists(dbPath)) {
            Files.createDirectories(dbPath.parent)
            Files.createFile(dbPath)
        }

        val gson = Gson()
        val mapType = object : TypeToken<ConcurrentHashMap<String, Long>>() {}.type
        val loadedMap: MutableMap<String, Long>? =
            gson.fromJson(FileReader(dbPath.toFile()), mapType)
        map = loadedMap ?: ConcurrentHashMap()
    }

    fun save() {
        if (!Files.exists(dbPath)) {
            Files.createDirectories(dbPath.parent)
            Files.createFile(dbPath)
        }

        val gson = Gson()
        FileWriter(dbPath.toFile()).use { writer ->
            gson.toJson(map, writer)
        }
    }
}
