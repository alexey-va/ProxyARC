package ru.arc.ai.routing.router

/**
 * Router output intents. Each actionable intent maps to an [ru.arc.ai.routing.dispatch.IntentHandler].
 *
 * To add a scenario:
 * 1. Add enum value + [wireName]
 * 2. Update router prompt ([RouterPrompt], prompts/router.txt)
 * 3. Implement handler in `dispatch/handlers/`
 * 4. Register in [ru.arc.ai.routing.dispatch.IntentHandlers]
 * 5. Add scenario block in assistant.yml + `routing.enabled-intents`
 */
enum class RouteIntent {
    SKIP,
    CHAT,
    BUG,
    ;

    fun wireName(): String =
        when (this) {
            SKIP -> "skip"
            CHAT -> "chat"
            BUG -> "bug"
        }

    companion object {
        fun fromWire(value: String): RouteIntent? =
            when (value.trim().lowercase()) {
                "skip" -> SKIP
                "chat" -> CHAT
                "bug", "bug_new", "bug_followup" -> BUG
                else -> null
            }
    }
}
