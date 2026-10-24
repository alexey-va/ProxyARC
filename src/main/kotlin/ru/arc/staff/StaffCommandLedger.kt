package ru.arc.staff

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ru.arc.persistence.AtomicFileStore
import java.nio.file.Path

/** Reserve before dispatch; an unknown outcome is never retried automatically, including after restart. */
internal class StaffCommandLedger(root: Path) {
    private val gson = Gson()
    private val type = object : TypeToken<Map<String, Long>>() {}.type
    private val store = AtomicFileStore(
        root, Path.of("data/staff-command-replay.json"), 1_048_576,
        encode = { value: Map<String, Long> -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
        decode = { bytes -> gson.fromJson<Map<String, Long>>(bytes.toString(Charsets.UTF_8), type) },
        validate = { values ->
            require(values.size <= 4096 && values.all { (key, expiry) -> key.matches(Regex("[a-z0-9:._-]{1,256}")) && expiry > 0 })
        },
    )
    init { store.loadOrDefault { emptyMap() } }

    fun claim(eventId: String, now: Long): Boolean = synchronized(reloadLock) {
        // Lifecycle generations can briefly coexist while already accepted operations drain.
        val claimed = store.loadOrDefault { emptyMap() }
        if ((claimed[eventId] ?: 0) > now) return false
        val remaining = claimed.filterValues { it > now }
        check(remaining.size < 4096) { "Staff command replay journal full" }
        // Keep rejected outcomes too: changing input or identity cannot reuse the provider event id.
        store.write(remaining + (eventId to (now + 600_000)))
        return true
    }

    private companion object { val reloadLock = Any() }
}
