package ru.arc.ai.memory

import com.google.gson.Gson
import org.slf4j.LoggerFactory
import ru.arc.xserver.RedisOperations
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent assistant memory backed by Redis hash, with in-memory cache.
 */
class AssistantMemoryStore(
    private val redis: RedisOperations?,
    private val storageKey: String = DEFAULT_STORAGE_KEY,
) {
    private val log = LoggerFactory.getLogger(AssistantMemoryStore::class.java)
    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, AssistantFact>()
    private val loaded = java.util.concurrent.atomic.AtomicBoolean(false)

    fun ensureLoaded() {
        if (loaded.get() || redis == null) {
            loaded.set(true)
            return
        }
        try {
            val map = redis.loadMap(storageKey).get()
            cache.clear()
            for ((id, json) in map) {
                if (json.isNullOrBlank()) continue
                runCatching { gson.fromJson(json, AssistantFact::class.java) }
                    .onSuccess { cache[id] = it }
                    .onFailure { log.warn("Skip corrupt fact {}: {}", id, it.message) }
            }
            loaded.set(true)
        } catch (e: Exception) {
            log.warn("Failed to load assistant facts from Redis: {}", e.message)
            loaded.set(true)
        }
    }

    fun remember(
        subject: String?,
        fact: String,
        confidence: Double,
        source: String? = null,
    ): AssistantFact {
        ensureLoaded()
        val normalized =
            AssistantFact(
                subject = subject?.trim()?.takeIf { it.isNotEmpty() },
                fact = fact.trim(),
                confidence = confidence.coerceIn(0.0, 1.0),
                source = source,
            )
        cache[normalized.id] = normalized
        persist(normalized)
        return normalized
    }

    fun forget(
        factId: String? = null,
        subject: String? = null,
        factContains: String? = null,
    ): Int {
        ensureLoaded()
        val ids =
            cache.values.filter { fact ->
                when {
                    !factId.isNullOrBlank() -> fact.id.equals(factId, ignoreCase = true)
                    !subject.isNullOrBlank() && !factContains.isNullOrBlank() ->
                        fact.subject.equals(subject, ignoreCase = true) &&
                            fact.fact.contains(factContains, ignoreCase = true)
                    !subject.isNullOrBlank() ->
                        fact.subject.equals(subject, ignoreCase = true)
                    !factContains.isNullOrBlank() ->
                        fact.fact.contains(factContains, ignoreCase = true)
                    else -> false
                }
            }.map { it.id }
        ids.forEach { id ->
            cache.remove(id)
            redis?.saveMapEntries(storageKey, id, null)?.get()
        }
        return ids.size
    }

    fun list(minConfidence: Double = 0.0, limit: Int = Int.MAX_VALUE): List<AssistantFact> {
        ensureLoaded()
        return cache.values
            .filter { it.confidence >= minConfidence }
            .sortedByDescending { it.rememberedAt }
            .take(limit)
    }

    fun formatForPrompt(minConfidence: Double, maxFacts: Int): String? {
        val facts = list(minConfidence, maxFacts)
        if (facts.isEmpty()) return null
        return facts.joinToString("\n") { fact ->
            val who = fact.subject ?: "общее"
            val conf = "%.2f".format(fact.confidence)
            "- [$fact.rememberedAt] ($conf) $who: ${fact.fact}"
        }
    }

    private fun persist(fact: AssistantFact) {
        redis?.saveMapEntries(storageKey, fact.id, gson.toJson(fact))?.get()
    }

    /** Test-only: replace all facts. */
    internal fun replaceAll(facts: Collection<AssistantFact>) {
        cache.clear()
        facts.forEach { cache[it.id] = it }
        loaded.set(true)
    }

    companion object {
        const val DEFAULT_STORAGE_KEY = "arc.ai.assistant.facts"
    }
}
