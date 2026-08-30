package ru.arc.join

import ru.arc.FirstJoinData
import ru.arc.core.TaskScheduler
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class JoinAnnouncementKind {
    FIRST_TIME,
    JOIN,
    LEAVE,
}

data class AnnouncementPermissions(
    val external: Boolean,
)

data class PublishedAnnouncement(
    val playerName: String,
    val kind: JoinAnnouncementKind,
    val customMessage: String?,
    val publishExternally: Boolean,
)

interface AnnouncementPlayer {
    val playerId: UUID
    val playerName: String
    val connectionIdentity: Any
    val active: Boolean
}

interface JoinMessageSource {
    fun load(playerName: String, kind: JoinAnnouncementKind): CompletableFuture<String?>
}

interface JoinAnnouncementSink {
    fun publish(announcement: PublishedAnnouncement)
}

interface ProxyLifecycle {
    val shuttingDown: Boolean
}

interface JoinSessionAnnouncements {
    fun onPostLogin(player: AnnouncementPlayer, permissions: AnnouncementPermissions)

    fun onDisconnect(player: AnnouncementPlayer)
}

class JoinAnnouncementService(
    private val firstJoinData: FirstJoinData,
    private val messageSource: JoinMessageSource,
    private val sink: JoinAnnouncementSink,
    private val scheduler: TaskScheduler,
    private val lifecycle: ProxyLifecycle,
) : JoinSessionAnnouncements {
    private val sessions = ConcurrentHashMap<UUID, ActiveSession>()
    private val latestGeneration = ConcurrentHashMap<UUID, Long>()
    private val generationSequence = AtomicLong()

    override fun onPostLogin(player: AnnouncementPlayer, permissions: AnnouncementPermissions) {
        val generation = generationSequence.incrementAndGet()
        val session = ActiveSession(player, permissions, generation)
        latestGeneration[player.playerId] = generation
        sessions[player.playerId] = session
        val firstJoin = firstJoinData.claimFirstJoin(player.playerName)
        firstJoin.persisted.whenComplete { _, error ->
            if (error != null) {
                log.error("Could not persist first-join claim for {}", player.playerName, error)
            }
        }
        val kind =
            if (firstJoin.firstTime) {
                JoinAnnouncementKind.FIRST_TIME
            } else {
                JoinAnnouncementKind.JOIN
            }

        scheduler.runLater(20) {
            if (!isCurrent(session)) return@runLater
            if (kind == JoinAnnouncementKind.FIRST_TIME) {
                sink.publish(PublishedAnnouncement(player.playerName, kind, null, permissions.external))
            } else {
                messageSource.load(player.playerName, kind).whenComplete { customMessage, error ->
                    if (!isCurrent(session)) return@whenComplete
                    if (error != null) {
                        log.warn("Could not load {} message for {}", kind, player.playerName, error)
                    }
                    sink.publish(PublishedAnnouncement(player.playerName, kind, customMessage, permissions.external))
                }
            }
        }
    }

    override fun onDisconnect(player: AnnouncementPlayer) {
        val current = sessions[player.playerId] ?: return
        if (current.player.connectionIdentity === player.connectionIdentity) {
            if (!sessions.remove(player.playerId, current)) return
            scheduler.runLater(20) {
                if (!isLatestDisconnected(current)) return@runLater
                messageSource.load(player.playerName, JoinAnnouncementKind.LEAVE).whenComplete { customMessage, error ->
                    if (!isLatestDisconnected(current)) return@whenComplete
                    if (error != null) {
                        log.warn("Could not load leave message for {}", player.playerName, error)
                    }
                    sink.publish(
                        PublishedAnnouncement(
                            player.playerName,
                            JoinAnnouncementKind.LEAVE,
                            customMessage,
                            current.permissions.external,
                        ),
                    )
                    latestGeneration.remove(player.playerId, current.generation)
                }
            }
        }
    }

    private fun isCurrent(session: ActiveSession): Boolean =
        !lifecycle.shuttingDown &&
            session.player.active &&
            sessions[session.player.playerId] === session

    private fun isLatestDisconnected(session: ActiveSession): Boolean =
        !lifecycle.shuttingDown &&
            sessions[session.player.playerId] == null &&
            latestGeneration[session.player.playerId] == session.generation

    private data class ActiveSession(
        val player: AnnouncementPlayer,
        val permissions: AnnouncementPermissions,
        val generation: Long,
    )

    companion object {
        private val log = LoggerFactory.getLogger(JoinAnnouncementService::class.java)
    }
}
