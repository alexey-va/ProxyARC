package ru.arc.ai

enum class AssistantRunMode {
    CHAT,
    BUG,
    ;

    /** Top-level key in assistant.yml for this scenario. */
    fun configSection(): String =
        when (this) {
            CHAT -> "chat"
            BUG -> "bug"
        }
}
