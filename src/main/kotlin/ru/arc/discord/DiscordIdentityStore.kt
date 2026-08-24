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

internal class DiscordIdentityStore(
    private val path: Path,
    private val gson: Gson = Common.prettyGson,
) {
    private val lock = ReentrantLock()
    private var state = DiscordIdentityState()
    @Volatile
    private var loadFailure: Throwable? = null

    init {
        load()
    }

    fun isAvailable(): Boolean = loadFailure == null

    fun failureClass(): String? = loadFailure?.javaClass?.simpleName

    fun <T> read(block: (DiscordIdentityState) -> T): T =
        lock.withLock {
            ensureAvailable()
            block(state.copyDeep())
        }

    fun <T> mutate(block: (DiscordIdentityState) -> T): T =
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
                    state = DiscordIdentityState()
                    return
                }
                Files.newBufferedReader(path).use { reader ->
                    val loaded = gson.fromJson(reader, DiscordIdentityState::class.java)
                        ?: error("identity state is null")
                    require(loaded.schemaVersion == CURRENT_SCHEMA_VERSION) {
                        "unsupported identity schema ${loaded.schemaVersion}"
                    }
                    loaded.normalizeCollections()
                    loaded.validate()
                    state = loaded
                }
            } catch (error: Exception) {
                loadFailure = error
                log.error(
                    "Discord identity storage disabled: {}",
                    error.javaClass.simpleName,
                )
            }
        }
    }

    private fun persist(candidate: DiscordIdentityState) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "${path.fileName}.", ".tmp")
        try {
            Files.newBufferedWriter(temp).use { writer -> gson.toJson(candidate, writer) }
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
            setOwnerOnlyPermissions(temp)
            try {
                Files.move(
                    temp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(path)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun ensureAvailable() {
        val failure = loadFailure
        if (failure != null) throw DiscordIdentityStoreUnavailableException(failure)
    }

    private fun setOwnerOnlyPermissions(file: Path) {
        runCatching {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordIdentityStore::class.java)
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

internal class DiscordIdentityStoreUnavailableException(cause: Throwable) :
    IllegalStateException("Discord identity storage is unavailable", cause)

internal class DiscordIdentityState {
    var schemaVersion: Int = DiscordIdentityStore.CURRENT_SCHEMA_VERSION
    var links: MutableList<StoredDiscordIdentityLink> = mutableListOf()
    var challenges: MutableList<StoredDiscordChallenge> = mutableListOf()
    var issueRates: MutableList<StoredRateWindow> = mutableListOf()
    var attemptRates: MutableList<StoredRateWindow> = mutableListOf()
    var audit: MutableList<StoredDiscordIdentityAudit> = mutableListOf()

    fun normalizeCollections() {
        links = links.ifEmpty { mutableListOf() }
        challenges = challenges.ifEmpty { mutableListOf() }
        issueRates = issueRates.ifEmpty { mutableListOf() }
        attemptRates = attemptRates.ifEmpty { mutableListOf() }
        audit = audit.ifEmpty { mutableListOf() }
    }

    fun validate() {
        require(links.map { it.playerUuid }.toSet().size == links.size) { "duplicate Minecraft identity" }
        require(links.map { it.discordUserId }.toSet().size == links.size) { "duplicate Discord identity" }
        links.forEach { link ->
            require(runCatching { UUID.fromString(link.playerUuid) }.isSuccess) { "invalid Minecraft UUID" }
            require(PLAYER_NAME.matches(link.playerName)) { "invalid Minecraft player name" }
            require(DiscordVerificationConfig.validSnowflake(link.discordUserId)) { "invalid Discord identity" }
            require(link.linkedAt >= 0 && link.updatedAt >= link.linkedAt) { "invalid identity timestamps" }
        }
        require(challenges.map { it.id }.toSet().size == challenges.size) { "duplicate challenge id" }
        challenges.forEach { challenge ->
            require(challenge.id.isNotBlank() && challenge.id.length <= 64) { "invalid challenge id" }
            require(runCatching { UUID.fromString(challenge.playerUuid) }.isSuccess) { "invalid challenge UUID" }
            require(PLAYER_NAME.matches(challenge.playerName)) { "invalid challenge player name" }
            require(HASH.matches(challenge.codeHash)) { "invalid challenge digest" }
            require(runCatching { DiscordChallengePurpose.valueOf(challenge.purpose) }.isSuccess) {
                "invalid challenge purpose"
            }
            require(challenge.createdAt >= 0 && challenge.expiresAt > challenge.createdAt) {
                "invalid challenge timestamps"
            }
            listOfNotNull(
                challenge.previousDiscordUserId,
                challenge.claimedByDiscordUserId,
                challenge.completedDiscordUserId,
            ).forEach { require(DiscordVerificationConfig.validSnowflake(it)) { "invalid challenge Discord id" } }
        }
        require(issueRates.size <= MAX_RATE_WINDOWS && attemptRates.size <= MAX_RATE_WINDOWS) {
            "too many rate windows"
        }
        require(audit.size <= MAX_AUDIT_EVENTS) { "too many audit events" }
    }

    fun copyDeep(): DiscordIdentityState =
        DiscordIdentityState().also { copy ->
            copy.schemaVersion = schemaVersion
            copy.links = links.mapTo(mutableListOf(), StoredDiscordIdentityLink::copy)
            copy.challenges = challenges.mapTo(mutableListOf(), StoredDiscordChallenge::copy)
            copy.issueRates = issueRates.mapTo(mutableListOf(), StoredRateWindow::copy)
            copy.attemptRates = attemptRates.mapTo(mutableListOf(), StoredRateWindow::copy)
            copy.audit = audit.mapTo(mutableListOf(), StoredDiscordIdentityAudit::copy)
        }

    companion object {
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        private val HASH = Regex("[0-9a-f]{64}")
        private const val MAX_RATE_WINDOWS = 10_000
        private const val MAX_AUDIT_EVENTS = 2_000
    }
}

internal data class StoredDiscordIdentityLink(
    var playerUuid: String = "",
    var playerName: String = "",
    var discordUserId: String = "",
    var linkedAt: Long = 0,
    var updatedAt: Long = 0,
)

internal data class StoredDiscordChallenge(
    var id: String = "",
    var purpose: String = DiscordChallengePurpose.LINK.name,
    var playerUuid: String = "",
    var playerName: String = "",
    var previousDiscordUserId: String? = null,
    var codeHash: String = "",
    var createdAt: Long = 0,
    var expiresAt: Long = 0,
    var claimedByDiscordUserId: String? = null,
    var claimExpiresAt: Long = 0,
    var completedDiscordUserId: String? = null,
    var completedAt: Long = 0,
)

internal data class StoredRateWindow(
    var key: String = "",
    var windowStartedAt: Long = 0,
    var count: Int = 0,
    var nextAllowedAt: Long = 0,
)

internal data class StoredDiscordIdentityAudit(
    var timestamp: Long = 0,
    var event: String = "",
    var outcome: String = "",
    var playerUuid: String? = null,
    var playerName: String? = null,
    var discordUserId: String? = null,
    var previousDiscordUserId: String? = null,
    var detail: String? = null,
)
