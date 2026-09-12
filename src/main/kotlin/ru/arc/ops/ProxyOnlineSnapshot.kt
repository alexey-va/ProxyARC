package ru.arc.ops

import com.velocitypowered.api.proxy.ProxyServer
import java.time.Instant
import java.util.Locale

data class ProxyOnlineSnapshot(
    val available: Boolean,
    val complete: Boolean,
    val names: List<String>,
    val sampledAt: Instant,
    val reason: String,
) {
    fun json(): String =
        ProxyOpsJson.ok(
            "schema" to "proxyarc.online.v1",
            "source" to "proxyarc-ops-http",
            "scope" to "proxy",
            "available" to available,
            "complete" to complete,
            "sampled_at" to sampledAt.epochSecond,
            "count" to names.size,
            "players" to names.map { mapOf("name" to it) },
            "reason" to reason,
        )

    companion object {
        private const val MAX_PLAYERS = 4096
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")

        fun capture(proxy: ProxyServer?): ProxyOnlineSnapshot {
            if (proxy == null) {
                return ProxyOnlineSnapshot(false, false, emptyList(), Instant.now(), "proxy_unavailable")
            }
            return sample(
                players = { proxy.allPlayers.asSequence().take(MAX_PLAYERS + 1).map { it.username }.toList() },
                count = { proxy.playerCount },
            )
        }

        // Velocity's collection is not guaranteed to be a snapshot. Require two
        // consistent observations; a changing roster is not evidence of emptiness.
        internal fun sample(
            players: () -> List<String>,
            count: () -> Int,
            now: () -> Instant = Instant::now,
        ): ProxyOnlineSnapshot {
            val started = now()
            val before = count()
            val first = players().sorted()
            val second = players().sorted()
            val after = count()
            val sampledAt = now()
            val validNames = first.all(PLAYER_NAME::matches) && first.map { it.lowercase(Locale.ROOT) }.distinct().size == first.size
            val complete =
                first.size <= MAX_PLAYERS && validNames && first == second &&
                    before == first.size && after == first.size &&
                    !sampledAt.isBefore(started) && sampledAt.toEpochMilli() - started.toEpochMilli() <= 2000
            return ProxyOnlineSnapshot(true, complete, first.take(MAX_PLAYERS), sampledAt, if (complete) "consistent_observations" else "roster_changed_or_incomplete")
        }
    }
}
