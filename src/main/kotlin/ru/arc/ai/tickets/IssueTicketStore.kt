package ru.arc.ai.tickets

import com.google.gson.Gson
import org.slf4j.LoggerFactory
import ru.arc.redis.RedisOperations
import java.util.concurrent.ConcurrentHashMap

object IssueTicketStore {
    private val log = LoggerFactory.getLogger(IssueTicketStore::class.java)
    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, IssueTicket>()
    private var redis: RedisOperations? = null
    @Volatile
    private var loaded = false

    const val STORAGE_KEY = "arc.issue.tickets"
    const val COUNTER_KEY = "arc.issue.tickets.counter"

    @Synchronized
    fun bind(redisOps: RedisOperations?) {
        redis = redisOps
        loaded = false
        cache.clear()
        ensureLoaded()
    }

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        val r = redis
        if (r == null) {
            loaded = true
            return
        }
        try {
            val map = r.loadMap(STORAGE_KEY).get()
            cache.clear()
            for ((id, json) in map) {
                if (json.isNullOrBlank()) continue
                runCatching {
                    val ticket: IssueTicket? = gson.fromJson(json, IssueTicket::class.java)
                    requireNotNull(ticket) { "ticket JSON is null" }
                }
                    .onSuccess { cache[id] = it }
                    .onFailure { log.warn("Skip corrupt ticket {}: {}", id, it.message) }
            }
            loaded = true
        } catch (e: Exception) {
            log.warn("Failed to load issue tickets: {}", e.message)
        }
    }

    @Synchronized
    fun nextTicketId(): String {
        ensureLoaded()
        val next = readCounter() + 1
        writeCounter(next)
        return "RB-%05d".format(next)
    }

    @Synchronized
    fun save(ticket: IssueTicket) {
        ensureLoaded()
        persist(ticket)
        cache[ticket.ticketId] = ticket
    }

    fun find(ticketId: String): IssueTicket? {
        ensureLoaded()
        val needle = ticketId.trim()
        cache[needle]?.let { return it }
        val upper = needle.uppercase()
        return cache.values.firstOrNull { it.ticketId.equals(upper, ignoreCase = true) }
    }

    fun findOpenByReporter(reporter: String): IssueTicket? {
        ensureLoaded()
        return cache.values
            .filter { it.isOpen() && it.reporter.equals(reporter, ignoreCase = true) }
            .maxByOrNull { it.createdAt }
    }

    fun listRecent(limit: Int, reporter: String? = null): List<IssueTicket> {
        ensureLoaded()
        val filtered =
            cache.values.filter { ticket ->
                reporter.isNullOrBlank() ||
                    ticket.reporter.equals(reporter.trim(), ignoreCase = true)
            }
        return filtered.sortedByDescending { it.createdAt }.take(limit.coerceIn(1, 50))
    }

    fun listOpenRecent(limit: Int): List<IssueTicket> {
        ensureLoaded()
        return cache.values
            .filter { it.isOpen() }
            .sortedByDescending { it.createdAt }
            .take(limit.coerceIn(1, 50))
    }

    fun mergeFromForum(parsed: IssueTicket) {
        ensureLoaded()
        val existing = find(parsed.ticketId)
        val merged =
            if (existing != null) {
                parsed.copy(
                    starterMessageId = existing.starterMessageId ?: parsed.starterMessageId,
                    createdAt = if (existing.createdAt > 0L) existing.createdAt else parsed.createdAt,
                    summary = parsed.summary ?: existing.summary,
                    server = parsed.server ?: existing.server,
                    title = if (parsed.title.isNotBlank()) parsed.title else existing.title,
                    reporter =
                        if (parsed.reporter != "unknown") {
                            parsed.reporter
                        } else {
                            existing.reporter
                        },
                )
            } else {
                parsed
            }
        save(merged)
    }

    /**
     * Forum sync only merges existing threads; deleting a Discord thread does not clear Redis.
     * Close open tickets whose thread no longer exists in the forum channel.
     */
    fun reconcileForumThreads(presentThreadIds: Set<String>): Int {
        ensureLoaded()
        val stale =
            cache.values.filter { ticket ->
                ticket.isOpen() &&
                    ticket.threadId.isNotBlank() &&
                    !presentThreadIds.contains(ticket.threadId)
            }
        stale.forEach { ticket ->
            save(ticket.copy(status = IssueTicket.STATUS_CLOSED))
            log.info(
                "Closed ticket {} — forum thread {} no longer present",
                ticket.ticketId,
                ticket.threadId,
            )
        }
        return stale.size
    }

    @Synchronized
    fun delete(ticketId: String): Boolean {
        ensureLoaded()
        val id = ticketId.trim()
        if (id.isEmpty()) return false
        val storedId = cache.keys.firstOrNull { it.equals(id, ignoreCase = true) } ?: return false
        redis?.saveMapEntries(STORAGE_KEY, storedId, null)?.get()
        return cache.remove(storedId) != null
    }

    private fun readCounter(): Int {
        val cachedMax =
            cache.keys.maxOfOrNull { id ->
                id.removePrefix("RB-").toIntOrNull() ?: 0
            } ?: 0
        val r = redis ?: return cachedMax
        val stored =
            runCatching {
            r.loadMapEntries(COUNTER_KEY, "n").get().firstOrNull()?.toIntOrNull() ?: 0
            }.getOrDefault(0)
        return maxOf(stored, cachedMax)
    }

    private fun writeCounter(value: Int) {
        redis?.saveMapEntries(COUNTER_KEY, "n", value.toString())?.get()
    }

    private fun persist(ticket: IssueTicket) {
        redis?.saveMapEntries(STORAGE_KEY, ticket.ticketId, gson.toJson(ticket))?.get()
    }

    internal fun replaceAll(tickets: Collection<IssueTicket>) {
        cache.clear()
        tickets.forEach { cache[it.ticketId] = it }
        loaded = true
    }
}
