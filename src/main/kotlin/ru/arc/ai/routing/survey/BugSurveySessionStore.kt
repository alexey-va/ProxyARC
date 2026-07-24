package ru.arc.ai.routing.survey

import org.slf4j.LoggerFactory
import ru.arc.ai.routing.ingress.InboundMeta
import java.util.concurrent.ConcurrentHashMap

object BugSurveySessionStore {
    private val log = LoggerFactory.getLogger(BugSurveySessionStore::class.java)
    private val sessions = ConcurrentHashMap<String, BugSurveySession>()
    private val participantIndex = ConcurrentHashMap<String, String>()

    private fun key(player: String): String = player.trim().lowercase()

    fun get(player: String): BugSurveySession? = sessions[key(player)]

    fun isActive(player: String): Boolean = findForPlayer(player) != null

    fun findForPlayer(player: String): BugSurveySession? {
        val k = key(player)
        sessions[k]?.let { return it }
        val primaryKey = participantIndex[k] ?: return null
        return sessions[primaryKey]
    }

    fun resolveSession(
        player: String,
        message: String,
        meta: InboundMeta,
        globalInquiryWindowMs: Long,
    ): BugSurveySession? {
        findForPlayer(player)?.let { return it }
        return findGlobalInquiryForRespondent(player, message, meta, globalInquiryWindowMs)
    }

    fun findGlobalInquiryForRespondent(
        respondent: String,
        message: String,
        meta: InboundMeta,
        windowMs: Long,
    ): BugSurveySession? {
        if (windowMs <= 0) return null
        val now = System.currentTimeMillis()
        val candidates =
            sessions.values.filter { session ->
                session.awaitingGlobalResponses &&
                    now - session.lastGlobalAskedAtMs <= windowMs &&
                    !session.isPrimary(respondent)
            }
        if (candidates.isEmpty()) return null
        val linkedToBot = meta.replyToBot || meta.continuationWithBot
        val shortConfirm = SurveyResponseHeuristic.isShortConfirmation(message)
        if (!linkedToBot && !shortConfirm) return null
        return candidates.maxByOrNull { it.lastGlobalAskedAtMs }
    }

    fun openOrTouch(player: String): BugSurveySession {
        val k = key(player)
        val now = System.currentTimeMillis()
        val existing = sessions[k]
        if (existing != null) {
            existing.lastActivityAtMs = now
            addParticipant(existing.player, existing.player)
            return existing
        }
        val session =
            BugSurveySession(
                player = player.trim(),
                startedAtMs = now,
                lastActivityAtMs = now,
            )
        sessions[k] = session
        addParticipant(player, player)
        log.info("Bug survey opened for {}", player)
        return session
    }

    fun touch(player: String) {
        findForPlayer(player)?.lastActivityAtMs = System.currentTimeMillis()
    }

    fun addParticipant(
        primaryReporter: String,
        participant: String,
    ) {
        val session = sessions[key(primaryReporter)] ?: return
        val name = participant.trim()
        if (name.isEmpty()) return
        session.participants.add(name)
        participantIndex[key(name)] = key(primaryReporter)
        session.lastActivityAtMs = System.currentTimeMillis()
        if (!session.isPrimary(name)) {
            log.info("Bug survey participant {} joined investigation of {}", name, primaryReporter)
        }
    }

    fun hasRecentGlobalAsk(
        primaryReporter: String,
        question: String,
        windowMs: Long,
    ): Boolean {
        if (windowMs <= 0) return false
        val session = findForPlayer(primaryReporter) ?: return false
        val previous = session.lastGlobalQuestion?.trim().orEmpty()
        if (previous.isEmpty()) return false
        if (System.currentTimeMillis() - session.lastGlobalAskedAtMs > windowMs) return false
        return previous.equals(question.trim(), ignoreCase = true)
    }

    fun markAwaitingGlobalResponses(
        primaryReporter: String,
        question: String,
    ) {
        val session = openOrTouch(primaryReporter)
        session.awaitingGlobalResponses = true
        session.lastGlobalQuestion = question.trim().takeIf { it.isNotEmpty() }
        session.lastGlobalAskedAtMs = System.currentTimeMillis()
        session.lastActivityAtMs = session.lastGlobalAskedAtMs
        log.info("Bug survey global inquiry for {}: {}", primaryReporter, session.lastGlobalQuestion)
    }

    fun bindTicket(
        player: String,
        ticketId: String,
        topicHint: String?,
    ) {
        val session = findForPlayer(player) ?: sessions[key(player)] ?: return
        session.ticketId = ticketId.trim()
        session.topicHint = topicHint?.trim()?.takeIf { it.isNotEmpty() }
        session.lastActivityAtMs = System.currentTimeMillis()
        log.info("Bug survey bound ticket {} for {}", ticketId, session.player)
    }

    fun close(
        player: String,
        reason: String,
    ): Boolean {
        val session = findForPlayer(player) ?: return false
        return closeSession(session, reason)
    }

    private fun closeSession(
        session: BugSurveySession,
        reason: String,
    ): Boolean {
        val k = key(session.player)
        val removed = sessions.remove(k)
        session.participants.forEach { participantIndex.remove(key(it)) }
        participantIndex.remove(k)
        if (removed != null) {
            log.info("Bug survey closed for {} reason={}", session.player, reason)
            return true
        }
        return false
    }

    fun closeIdle(timeoutMs: Long): Int {
        if (timeoutMs <= 0) return 0
        val now = System.currentTimeMillis()
        var closed = 0
        sessions.values.toList().forEach { session ->
            val idle = now - session.lastActivityAtMs > timeoutMs
            if (idle && closeSession(session, "idle_timeout")) {
                closed++
            }
        }
        return closed
    }

    fun clear() {
        sessions.clear()
        participantIndex.clear()
    }

    fun activeCount(): Int = sessions.size
}
