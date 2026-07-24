package ru.arc.ai.tickets

/** Discord forum embed titles for issue tickets. */
object IssueTicketTitles {
    const val CLOSED_PREFIX = "[Закрыт] "

    fun markClosed(title: String?): String {
        val raw = title?.trim().orEmpty().ifBlank { "баг" }
        val withoutPrefix =
            raw.removePrefix(CLOSED_PREFIX)
                .removePrefix("[Закрыт]")
                .trim()
                .ifBlank { "баг" }
        return (CLOSED_PREFIX + withoutPrefix).take(256)
    }

    fun isClosedTitle(title: String?): Boolean = title?.trim()?.startsWith(CLOSED_PREFIX) == true
}
