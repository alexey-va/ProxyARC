package ru.arc.ai.routing.survey

/**
 * High-confidence public replies to a bug-survey question.
 *
 * Keep this deliberately strict: a loose substring check such as `contains("да")`
 * also matches unrelated chat like `продам`.
 */
object SurveyResponseHeuristic {
    private val confirmationToken =
        Regex("""(?iu)(?:^|[\s,.!?])(?:да|ага|тоже|same|\+1)(?:$|[\s,.!?])""")

    fun isShortConfirmation(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.isEmpty() || lower.length > 60) return false
        if (lower == "у меня" || lower.startsWith("у меня ")) return true
        if (lower.contains("не работает") || lower.contains("не пашет")) return true
        return confirmationToken.containsMatchIn(lower)
    }
}
