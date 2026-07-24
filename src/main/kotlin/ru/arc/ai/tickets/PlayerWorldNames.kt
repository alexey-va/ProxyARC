package ru.arc.ai.tickets

/**
 * Player-facing world labels for tickets, PMs, and prompts.
 *
 * Players do not see Velocity/Paper ids (`classic`, `survival`, `parkour`) — only Russian world names.
 */
object PlayerWorldNames {
    private val PROXY_NAMES =
        mapOf(
            "classic" to "спавн",
            "spawn" to "спавн",
            "classic_survival" to "мир биомов",
            "survival" to "мир биомов",
            "parkour" to "паркур",
            "discord" to "discord",
            "velocity" to "прокси",
            "proxy" to "прокси",
            "minecraft-server" to "прокси",
        )

    private val WORLD_NAMES =
        mapOf(
            "spawn" to "спавн",
            "world" to "спавн",
            "survival" to "мир биомов",
            "vanilla" to "ванильный мир",
            "mining" to "мир майнинга",
            "parkour" to "паркур",
            "parkour1" to "паркур",
            "nether" to "ад",
            "world_nether" to "ад",
            "vanilla_nether" to "ванильный ад",
            "world_the_end" to "энд",
            "vanilla_the_end" to "ванильный энд",
        )

    private val TEXT_HINTS =
        listOf(
            "мир биомов" to "мир биомов",
            "мире биомов" to "мир биомов",
            "биомов" to "мир биомов",
            "мир майнинга" to "мир майнинга",
            "майнинг" to "мир майнинга",
            "mining" to "мир майнинга",
            "ванильный мир" to "ванильный мир",
            "ваниль" to "ванильный мир",
            "vanilla" to "ванильный мир",
            "данж" to "данжи",
            "dungeon" to "данжи",
            "em_" to "данжи",
            "spn_" to "данжи",
            "паркур" to "паркур",
            "parkour" to "паркур",
            "спавн" to "спавн",
            "spawn" to "спавн",
            "classic" to "спавн",
            "survival" to "мир биомов",
        )

    /** Best label for Discord / ticket suffix from proxy id, world id, or message text. */
    fun resolveDisplay(
        proxyOrHint: String?,
        messageText: String? = null,
        llmServerHint: String? = null,
    ): String {
        inferFromText(messageText)?.let { return it }
        inferFromText(llmServerHint)?.let { return it }
        return displayProxyOrWorld(proxyOrHint)
    }

    fun displayProxyOrWorld(raw: String?): String {
        if (raw.isNullOrBlank()) return "неизвестно"
        val key = raw.trim().lowercase()
        WORLD_NAMES[key]?.let { return it }
        PROXY_NAMES[key]?.let { return it }
        if (key.startsWith("em_") || key.startsWith("spn_")) return "данжи"
        return raw.trim()
    }

    fun inferFromText(text: String?): String? {
        val lower = text?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return null
        return TEXT_HINTS.firstOrNull { (needle, _) -> lower.contains(needle) }?.second
    }

    /**
     * Removes location tail LLM often embeds in ticket titles before we append canonical « · {мир}».
     * e.g. «/shop buy на survival - Survival» → «/shop buy»
     */
    fun stripEmbeddedLocations(topic: String): String {
        var current = topic.trim()
        for (ignored in 0 until 4) {
            val stripped = stripOneLocationLayer(current) ?: break
            current = stripped
        }
        return current.trim().trimEnd('·', '-', '—', '–', ',', '.', ';', ':').trim()
    }

    private fun stripOneLocationLayer(topic: String): String? {
        val lowerTopic = topic.lowercase()
        val tokens = locationTokens().sortedByDescending { it.length }
        for (token in tokens) {
            for (separator in listOf(" · ", " - ", " — ", " – ", " / ", " | ", " ·", " -")) {
                val suffix = separator + token
                if (lowerTopic.endsWith(suffix.lowercase())) {
                    return topic.substring(0, topic.length - suffix.length).trim()
                }
            }
            for (preposition in listOf(" на ", " on ", " in ")) {
                val suffix = preposition + token
                if (lowerTopic.endsWith(suffix.lowercase())) {
                    return topic.substring(0, topic.length - suffix.length).trim()
                }
            }
        }
        return null
    }

    private fun locationTokens(): List<String> =
        buildList {
            addAll(PROXY_NAMES.keys)
            addAll(PROXY_NAMES.values)
            addAll(WORLD_NAMES.keys)
            addAll(WORLD_NAMES.values)
            addAll(TEXT_HINTS.map { it.second })
            addAll(listOf("Survival", "Classic", "Parkour", "Spawn", "Discord", "Velocity"))
        }.distinctBy { it.lowercase() }
}
