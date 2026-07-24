package ru.arc.ai

enum class AssistantRunMode {
    CHAT,
    BUG,
    BUG_SURVEY,
    ;

    /** Top-level key in assistant.yml for this scenario. */
    fun configSection(): String =
        when (this) {
            CHAT -> "chat"
            BUG -> "bug"
            BUG_SURVEY -> "bug-survey"
        }

    fun usesBugPrompt(): Boolean = this == BUG || this == BUG_SURVEY

    fun blocksPublicReply(): Boolean = usesBugPrompt()
}
