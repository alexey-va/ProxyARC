package ru.arc.discord

import com.google.gson.Gson
import org.slf4j.LoggerFactory
import ru.arc.Common
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class DiscordNotificationKind {
    MENTIONS,
    AUCTION,
    TICKETS,
    PUNISHMENTS,
    EVENTS,
    INVITES,
}

internal data class DiscordNotificationPreferences(
    val discordUserId: String,
    val enabled: Set<DiscordNotificationKind>,
    val updatedAt: Long,
) {
    fun enabled(kind: DiscordNotificationKind): Boolean = kind in enabled
}

internal data class DiscordRecoveryRequest(
    val id: String,
    val playerUuid: UUID,
    val playerName: String,
    val discordUserId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val status: String,
)

internal data class DiscordGameEvent(
    val id: String,
    val name: String,
    val description: String,
    val startsAt: Long,
    val announcementMessageId: String?,
    val participantDiscordIds: Set<String>,
    val sentReminderMinutes: Set<Long>,
    val status: String,
    val winnerPlayerName: String?,
)

/** Atomic, owner-readable-only storage for opt-in preferences and Discord workflows. */
internal class DiscordIntegrationStore(
    private val path: Path,
    private val gson: Gson = Common.prettyGson,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock()
    private var state = StoredDiscordIntegrationState()
    @Volatile private var loadFailure: Throwable? = null

    init {
        load()
    }

    fun isAvailable(): Boolean = loadFailure == null

    fun failureClass(): String? = loadFailure?.javaClass?.simpleName

    fun preferences(discordUserId: String): DiscordNotificationPreferences =
        read { current ->
            current.preferences.firstOrNull { it.discordUserId == discordUserId }?.toDomain()
                ?: DiscordNotificationPreferences(discordUserId, emptySet(), 0)
        }

    fun enabledDiscordUserIds(kind: DiscordNotificationKind): Set<String> =
        read { current ->
            current.preferences.asSequence()
                .filter { kind.name in it.enabled }
                .mapTo(linkedSetOf()) { it.discordUserId }
        }

    fun toggle(
        discordUserId: String,
        kind: DiscordNotificationKind,
    ): DiscordNotificationPreferences =
        mutate { current ->
            val now = clock()
            val stored = current.preferences.firstOrNull { it.discordUserId == discordUserId }
                ?: StoredDiscordNotificationPreferences(discordUserId = discordUserId).also(current.preferences::add)
            val enabled = stored.enabled.mapNotNullTo(linkedSetOf()) { enumValueOrNull<DiscordNotificationKind>(it) }
            if (!enabled.add(kind)) enabled.remove(kind)
            stored.enabled = enabled.mapTo(mutableListOf(), DiscordNotificationKind::name)
            stored.updatedAt = now
            appendAudit(current, "notification-toggle", discordUserId, detail = "${kind.name}:${kind in enabled}")
            stored.toDomain()
        }

    fun createRecoveryRequest(
        link: DiscordIdentityLink,
        ttlSeconds: Long,
    ): DiscordRecoveryRequest =
        mutate { current ->
            val now = clock()
            prune(current, now)
            current.recoveryRequests.firstOrNull {
                it.discordUserId == link.discordUserId && it.status == STATUS_PENDING && it.expiresAt > now
            }?.toDomain() ?: run {
                val request =
                    StoredDiscordRecoveryRequest(
                        id = uniqueId(current.recoveryRequests.mapTo(hashSetOf()) { it.id }),
                        playerUuid = link.playerUuid.toString(),
                        playerName = link.playerName,
                        discordUserId = link.discordUserId,
                        createdAt = now,
                        expiresAt = now + ttlSeconds * 1_000,
                        status = STATUS_PENDING,
                    )
                current.recoveryRequests += request
                appendAudit(current, "recovery-request", link.discordUserId, link.playerName, request.id)
                request.toDomain()
            }
        }

    fun activeRecoveryRequest(discordUserId: String): DiscordRecoveryRequest? =
        read { current ->
            val now = clock()
            current.recoveryRequests.firstOrNull {
                it.discordUserId == discordUserId && it.status == STATUS_PENDING && it.expiresAt > now
            }?.toDomain()
        }

    fun activeEvent(): DiscordGameEvent? =
        read { current -> current.events.lastOrNull { it.status == EVENT_ACTIVE }?.toDomain() }

    fun createEvent(
        name: String,
        description: String,
        startsAt: Long,
    ): DiscordGameEvent =
        mutate { current ->
            check(current.events.none { it.status == EVENT_ACTIVE }) { "an event is already active" }
            val event =
                StoredDiscordGameEvent(
                    id = uniqueId(current.events.mapTo(hashSetOf()) { it.id }),
                    name = name,
                    description = description,
                    startsAt = startsAt,
                    status = EVENT_ACTIVE,
                )
            current.events += event
            trimEvents(current)
            appendAudit(current, "event-create", detail = event.id)
            event.toDomain()
        }

    fun setEventAnnouncement(
        eventId: String,
        messageId: String,
    ): DiscordGameEvent? =
        mutate { current ->
            current.events.firstOrNull { it.id == eventId }?.also { it.announcementMessageId = messageId }?.toDomain()
        }

    fun toggleEventParticipant(
        eventId: String,
        discordUserId: String,
    ): Pair<DiscordGameEvent, Boolean>? =
        mutate { current ->
            val event = current.events.firstOrNull { it.id == eventId && it.status == EVENT_ACTIVE }
                ?: return@mutate null
            val participants = event.participantDiscordIds.toMutableSet()
            val joined = participants.add(discordUserId)
            if (!joined) participants.remove(discordUserId)
            event.participantDiscordIds = participants.toMutableList()
            appendAudit(current, "event-participation", discordUserId, detail = "$eventId:$joined")
            event.toDomain() to joined
        }

    fun remindersDue(
        now: Long,
        reminderMinutes: Collection<Long>,
    ): List<Pair<DiscordGameEvent, Long>> =
        read { current ->
            current.events.filter { it.status == EVENT_ACTIVE && it.startsAt > now }.flatMap { stored ->
                reminderMinutes.filter { minutes ->
                    minutes !in stored.sentReminderMinutes && stored.startsAt - now <= minutes * 60_000
                }.map { stored.toDomain() to it }
            }
        }

    fun markReminderSent(
        eventId: String,
        minutes: Long,
    ) {
        mutate { current ->
            current.events.firstOrNull { it.id == eventId }?.let { event ->
                if (minutes !in event.sentReminderMinutes) event.sentReminderMinutes.add(minutes)
            }
        }
    }

    fun finishEvent(
        winnerPlayerName: String?,
        cancelled: Boolean,
    ): DiscordGameEvent? =
        mutate { current ->
            current.events.lastOrNull { it.status == EVENT_ACTIVE }?.also { event ->
                event.status = if (cancelled) EVENT_CANCELLED else EVENT_FINISHED
                event.winnerPlayerName = winnerPlayerName
                appendAudit(current, "event-finish", detail = "${event.id}:${event.status}")
            }?.toDomain()
        }

    fun cancelEvent(eventId: String): DiscordGameEvent? =
        mutate { current ->
            current.events.firstOrNull { it.id == eventId && it.status == EVENT_ACTIVE }?.also { event ->
                event.status = EVENT_CANCELLED
                appendAudit(current, "event-publish-failed", detail = event.id)
            }?.toDomain()
        }

    fun recordSecurityEvent(
        event: String,
        discordUserId: String?,
        playerName: String?,
        detail: String? = null,
    ) {
        mutate { current -> appendAudit(current, event, discordUserId, playerName, detail) }
    }

    private fun <T> read(block: (StoredDiscordIntegrationState) -> T): T =
        lock.withLock {
            ensureAvailable()
            block(state.copyDeep())
        }

    private fun <T> mutate(block: (StoredDiscordIntegrationState) -> T): T =
        lock.withLock {
            ensureAvailable()
            val candidate = state.copyDeep()
            val result = block(candidate)
            candidate.validate()
            persist(candidate)
            state = candidate
            result
        }

    private fun load() {
        lock.withLock {
            try {
                if (!Files.exists(path)) {
                    Files.createDirectories(path.parent)
                    state = StoredDiscordIntegrationState()
                    return
                }
                Files.newBufferedReader(path).use { reader ->
                    val loaded = gson.fromJson(reader, StoredDiscordIntegrationState::class.java)
                        ?: error("Discord integration state is null")
                    require(loaded.schemaVersion == CURRENT_SCHEMA_VERSION) {
                        "unsupported Discord integration schema ${loaded.schemaVersion}"
                    }
                    loaded.normalize()
                    loaded.validate()
                    state = loaded
                }
            } catch (error: Exception) {
                loadFailure = error
                log.error("Discord integration storage disabled: {}", error.javaClass.simpleName)
            }
        }
    }

    private fun persist(candidate: StoredDiscordIntegrationState) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "${path.fileName}.", ".tmp")
        try {
            Files.newBufferedWriter(temp).use { writer -> gson.toJson(candidate, writer) }
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            ownerOnly(temp)
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            ownerOnly(path)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun ownerOnly(file: Path) {
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }

    private fun ensureAvailable() {
        loadFailure?.let { throw IllegalStateException("Discord integration storage is unavailable", it) }
    }

    private fun prune(
        current: StoredDiscordIntegrationState,
        now: Long,
    ) {
        current.recoveryRequests.removeIf { it.expiresAt <= now && it.status == STATUS_PENDING }
    }

    private fun appendAudit(
        current: StoredDiscordIntegrationState,
        event: String,
        discordUserId: String? = null,
        playerName: String? = null,
        detail: String? = null,
    ) {
        current.audit +=
            StoredDiscordIntegrationAudit(
                timestamp = clock(),
                event = event.take(48),
                discordUserId = discordUserId,
                playerName = playerName,
                detail = detail?.take(120),
            )
        if (current.audit.size > MAX_AUDIT) current.audit = current.audit.takeLast(MAX_AUDIT).toMutableList()
    }

    private fun trimEvents(current: StoredDiscordIntegrationState) {
        if (current.events.size > MAX_EVENTS) current.events = current.events.takeLast(MAX_EVENTS).toMutableList()
    }

    private fun uniqueId(existing: Set<String>): String {
        repeat(10) {
            val candidate = UUID.randomUUID().toString().substring(0, 8).uppercase()
            if (candidate !in existing) return candidate
        }
        error("could not generate a unique Discord integration id")
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordIntegrationStore::class.java)
        const val CURRENT_SCHEMA_VERSION = 1
        private const val MAX_AUDIT = 2_000
        private const val MAX_EVENTS = 100
        const val STATUS_PENDING = "pending"
        const val EVENT_ACTIVE = "active"
        const val EVENT_FINISHED = "finished"
        const val EVENT_CANCELLED = "cancelled"
    }
}

private class StoredDiscordIntegrationState {
    var schemaVersion: Int = 1
    var preferences: MutableList<StoredDiscordNotificationPreferences> = mutableListOf()
    var recoveryRequests: MutableList<StoredDiscordRecoveryRequest> = mutableListOf()
    var events: MutableList<StoredDiscordGameEvent> = mutableListOf()
    var audit: MutableList<StoredDiscordIntegrationAudit> = mutableListOf()

    fun normalize() {
        preferences = preferences.ifEmpty { mutableListOf() }
        recoveryRequests = recoveryRequests.ifEmpty { mutableListOf() }
        events = events.ifEmpty { mutableListOf() }
        audit = audit.ifEmpty { mutableListOf() }
        events.forEach {
            it.participantDiscordIds = it.participantDiscordIds.ifEmpty { mutableListOf() }
            it.sentReminderMinutes = it.sentReminderMinutes.ifEmpty { mutableListOf() }
        }
    }

    fun validate() {
        require(preferences.size <= MAX_PREFERENCES) { "too many notification preferences" }
        require(preferences.map { it.discordUserId }.toSet().size == preferences.size) { "duplicate notification preferences" }
        preferences.forEach {
            require(DiscordVerificationConfig.validSnowflake(it.discordUserId)) { "invalid preference Discord id" }
            require(it.updatedAt >= 0) { "invalid notification preference timestamp" }
            require(it.enabled.all { value -> enumValueOrNull<DiscordNotificationKind>(value) != null }) {
                "invalid notification preference"
            }
            require(it.enabled.distinct().size == it.enabled.size && it.enabled.size <= DiscordNotificationKind.entries.size) {
                "duplicate notification preference kind"
            }
        }
        require(recoveryRequests.size <= MAX_RECOVERY_REQUESTS) { "too many recovery requests" }
        require(recoveryRequests.map { it.id }.toSet().size == recoveryRequests.size) { "duplicate recovery request id" }
        recoveryRequests.forEach {
            require(it.id.matches(Regex("[A-F0-9]{8}"))) { "invalid recovery request id" }
            require(runCatching { UUID.fromString(it.playerUuid) }.isSuccess) { "invalid recovery UUID" }
            require(PLAYER_NAME.matches(it.playerName)) { "invalid recovery player name" }
            require(DiscordVerificationConfig.validSnowflake(it.discordUserId)) { "invalid recovery Discord id" }
            require(it.expiresAt > it.createdAt) { "invalid recovery timestamps" }
            require(it.status == DiscordIntegrationStore.STATUS_PENDING) { "invalid recovery status" }
        }
        require(events.size <= MAX_EVENTS) { "too many Discord events" }
        require(events.count { it.status == DiscordIntegrationStore.EVENT_ACTIVE } <= 1) { "multiple active Discord events" }
        require(events.map { it.id }.toSet().size == events.size) { "duplicate event id" }
        events.forEach {
            require(it.id.matches(Regex("[A-F0-9]{8}"))) { "invalid event id" }
            require(it.name.length in 1..80 && it.description.length in 1..1_000) { "invalid event text" }
            require(it.startsAt >= 0) { "invalid event timestamp" }
            require(it.participantDiscordIds.size <= 10_000) { "too many event participants" }
            require(it.participantDiscordIds.distinct().size == it.participantDiscordIds.size) { "duplicate event participant" }
            require(it.participantDiscordIds.all(DiscordVerificationConfig::validSnowflake)) { "invalid event participant" }
            require(it.sentReminderMinutes.size <= 32 && it.sentReminderMinutes.all { value -> value > 0 }) {
                "invalid event reminders"
            }
            require(
                it.status in setOf(
                    DiscordIntegrationStore.EVENT_ACTIVE,
                    DiscordIntegrationStore.EVENT_FINISHED,
                    DiscordIntegrationStore.EVENT_CANCELLED,
                ),
            ) { "invalid event status" }
            require(it.announcementMessageId == null || DiscordVerificationConfig.validSnowflake(it.announcementMessageId!!)) {
                "invalid event announcement id"
            }
            require(it.winnerPlayerName == null || PLAYER_NAME.matches(it.winnerPlayerName!!)) { "invalid event winner" }
            require((it.status == DiscordIntegrationStore.EVENT_FINISHED) == (it.winnerPlayerName != null)) {
                "invalid event winner state"
            }
        }
        require(audit.size <= 2_000) { "too many integration audit events" }
        audit.forEach {
            require(it.timestamp >= 0 && it.event.length in 1..48) { "invalid integration audit event" }
            require(it.discordUserId == null || DiscordVerificationConfig.validSnowflake(it.discordUserId!!)) {
                "invalid integration audit Discord id"
            }
            require(it.playerName == null || PLAYER_NAME.matches(it.playerName!!)) { "invalid integration audit player" }
            require(it.detail == null || it.detail!!.length <= 120) { "invalid integration audit detail" }
        }
    }

    fun copyDeep(): StoredDiscordIntegrationState =
        StoredDiscordIntegrationState().also { copy ->
            copy.schemaVersion = schemaVersion
            copy.preferences = preferences.mapTo(mutableListOf()) { it.copy(enabled = it.enabled.toMutableList()) }
            copy.recoveryRequests = recoveryRequests.mapTo(mutableListOf(), StoredDiscordRecoveryRequest::copy)
            copy.events = events.mapTo(mutableListOf()) {
                it.copy(
                    participantDiscordIds = it.participantDiscordIds.toMutableList(),
                    sentReminderMinutes = it.sentReminderMinutes.toMutableList(),
                )
            }
            copy.audit = audit.mapTo(mutableListOf(), StoredDiscordIntegrationAudit::copy)
        }

    companion object {
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        private const val MAX_PREFERENCES = 20_000
        private const val MAX_RECOVERY_REQUESTS = 10_000
        private const val MAX_EVENTS = 100
    }
}

private data class StoredDiscordNotificationPreferences(
    var discordUserId: String = "",
    var enabled: MutableList<String> = mutableListOf(),
    var updatedAt: Long = 0,
) {
    fun toDomain() =
        DiscordNotificationPreferences(
            discordUserId,
            enabled.mapNotNullTo(linkedSetOf()) { enumValueOrNull<DiscordNotificationKind>(it) },
            updatedAt,
        )
}

private data class StoredDiscordRecoveryRequest(
    var id: String = "",
    var playerUuid: String = "",
    var playerName: String = "",
    var discordUserId: String = "",
    var createdAt: Long = 0,
    var expiresAt: Long = 0,
    var status: String = DiscordIntegrationStore.STATUS_PENDING,
) {
    fun toDomain() =
        DiscordRecoveryRequest(id, UUID.fromString(playerUuid), playerName, discordUserId, createdAt, expiresAt, status)
}

private data class StoredDiscordGameEvent(
    var id: String = "",
    var name: String = "",
    var description: String = "",
    var startsAt: Long = 0,
    var announcementMessageId: String? = null,
    var participantDiscordIds: MutableList<String> = mutableListOf(),
    var sentReminderMinutes: MutableList<Long> = mutableListOf(),
    var status: String = DiscordIntegrationStore.EVENT_ACTIVE,
    var winnerPlayerName: String? = null,
) {
    fun toDomain() =
        DiscordGameEvent(
            id,
            name,
            description,
            startsAt,
            announcementMessageId,
            participantDiscordIds.toSet(),
            sentReminderMinutes.toSet(),
            status,
            winnerPlayerName,
        )
}

private data class StoredDiscordIntegrationAudit(
    var timestamp: Long = 0,
    var event: String = "",
    var discordUserId: String? = null,
    var playerName: String? = null,
    var detail: String? = null,
)

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    runCatching { enumValueOf<T>(value) }.getOrNull()
