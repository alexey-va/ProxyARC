package ru.arc.ai.routing.survey

import ru.arc.ai.routing.ingress.InboundMeta

data class BugSurveySession(
    val player: String,
    val startedAtMs: Long,
    var lastActivityAtMs: Long,
    var ticketId: String? = null,
    var topicHint: String? = null,
    val participants: MutableSet<String> = linkedSetOf(),
    var awaitingGlobalResponses: Boolean = false,
    var lastGlobalQuestion: String? = null,
    var lastGlobalAskedAtMs: Long = 0,
) {
    fun isPrimary(name: String): Boolean = player.equals(name, ignoreCase = true)

    fun includes(name: String): Boolean =
        isPrimary(name) || participants.any { it.equals(name, ignoreCase = true) }
}
