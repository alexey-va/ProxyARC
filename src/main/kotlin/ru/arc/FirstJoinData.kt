package ru.arc

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ru.arc.persistence.AtomicFileStore
import ru.arc.persistence.CoalescingAsyncWriter
import ru.arc.velocity.Velocity
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class FirstJoinClaim(
    val firstTime: Boolean,
    val joinedAt: Long,
    val persisted: CompletableFuture<Unit>,
)

class FirstJoinData(
    dbPath: Path = checkNotNull(Velocity.dataFolder).resolve("first_time_join.json"),
    private val ioExecutor: ExecutorService = firstJoinExecutor(),
) {
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Long>>() {}.type
    private val normalizedPath = dbPath.toAbsolutePath().normalize()
    private val store =
        AtomicFileStore<Map<String, Long>>(
            checkNotNull(normalizedPath.parent) { "First-join data path must have a parent directory" },
            normalizedPath.fileName,
            MAX_FILE_BYTES,
            { snapshot -> gson.toJson(snapshot).toByteArray(StandardCharsets.UTF_8) },
            { bytes -> decode(bytes) },
            { snapshot -> require(snapshot.size <= MAX_PLAYERS) { "First-join data contains too many players" } },
        )
    private val writer =
        CoalescingAsyncWriter<Map<String, Long>> { snapshot ->
            CompletableFuture.supplyAsync(
                {
                    store.write(snapshot)
                    Unit
                },
                ioExecutor,
            )
        }

    @Volatile
    private var joinedPlayers: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    fun joinedAt(name: String): Long? = joinedPlayers[normalize(name)]

    fun claimFirstJoin(
        name: String,
        joinedAt: Long = System.currentTimeMillis(),
    ): FirstJoinClaim {
        require(joinedAt >= 0) { "First-join timestamp must not be negative" }
        val normalizedName = normalize(name)
        require(normalizedName.isNotEmpty()) { "Player name must not be blank" }
        val previous = joinedPlayers.putIfAbsent(normalizedName, joinedAt)
        if (previous != null) {
            return FirstJoinClaim(false, previous, CompletableFuture.completedFuture(Unit))
        }
        return FirstJoinClaim(true, joinedAt, writer.submit(snapshot()))
    }

    @Synchronized
    fun load() {
        joinedPlayers = ConcurrentHashMap(store.loadOrNull().orEmpty())
    }

    fun closeAsync(): CompletableFuture<Unit> =
        writer.submit(snapshot())
            .thenCompose { writer.closeAsync() }
            .whenComplete { _, _ -> ioExecutor.shutdown() }

    private fun snapshot(): Map<String, Long> = joinedPlayers.toMap()

    private fun decode(bytes: ByteArray): Map<String, Long> {
        val loaded: Map<String, Long>? =
            gson.fromJson(bytes.toString(StandardCharsets.UTF_8), mapType)
        val normalized = LinkedHashMap<String, Long>()
        loaded.orEmpty().forEach { (name, timestamp) ->
            val key = normalize(name)
            if (key.isNotEmpty() && timestamp >= 0) {
                normalized.merge(key, timestamp, ::minOf)
            }
        }
        return normalized
    }

    private fun normalize(name: String): String = name.trim().lowercase(Locale.ROOT)

    companion object {
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024
        private const val MAX_PLAYERS = 1_000_000

        private fun firstJoinExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "proxyarc-first-join-io").apply { isDaemon = true }
            }
    }
}
