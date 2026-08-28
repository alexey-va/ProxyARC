package ru.arc.telegram

import com.google.gson.Gson
import ru.arc.Common
import ru.arc.persistence.AtomicFileStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.UUID

internal class TelegramIdentityStore(
    dataRoot: Path,
    gson: Gson = Common.prettyGson,
) {
    private val store =
        AtomicFileStore(
            root = dataRoot,
            relativePath = Path.of("data/telegram-identities.json"),
            maxBytes = MAX_STORE_BYTES,
            encode = { state: StoredTelegramIdentityState -> gson.toJson(state).toByteArray() },
            decode = { bytes -> gson.fromJson(bytes.decodeToString(), StoredTelegramIdentityState::class.java) },
            validate = StoredTelegramIdentityState::validate,
        )
    private var state = store.loadOrDefault(::StoredTelegramIdentityState)

    init {
        securePermissions()
    }

    @Synchronized
    fun <T> read(block: (StoredTelegramIdentityState) -> T): T = block(state.copyDeep())

    @Synchronized
    fun <T> mutate(block: (StoredTelegramIdentityState) -> T): T {
        val candidate = state.copyDeep()
        val result = block(candidate)
        state = store.write(candidate)
        securePermissions()
        return result
    }

    private fun securePermissions() {
        if (!Files.exists(store.path)) return
        runCatching {
            Files.setPosixFilePermissions(store.path, PosixFilePermissions.fromString("rw-------"))
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private const val MAX_STORE_BYTES = 2L * 1024L * 1024L
    }
}

internal class StoredTelegramIdentityState {
    var schemaVersion: Int = TelegramIdentityStore.CURRENT_SCHEMA_VERSION
    var links: MutableList<StoredTelegramIdentityLink> = mutableListOf()
    var challenges: MutableList<StoredTelegramChallenge> = mutableListOf()
    var rates: MutableList<StoredTelegramRateWindow> = mutableListOf()
    var audit: MutableList<StoredTelegramIdentityAudit> = mutableListOf()

    fun validate() {
        require(schemaVersion == TelegramIdentityStore.CURRENT_SCHEMA_VERSION) { "unsupported Telegram identity schema" }
        require(links.size <= MAX_LINKS) { "too many Telegram identity links" }
        require(challenges.size <= MAX_CHALLENGES) { "too many Telegram identity challenges" }
        require(rates.size <= MAX_RATES) { "too many Telegram identity rate windows" }
        require(audit.size <= MAX_AUDIT) { "too many Telegram identity audit events" }
        require(links.map(StoredTelegramIdentityLink::playerUuid).toSet().size == links.size) {
            "duplicate Minecraft Telegram identity"
        }
        require(links.map(StoredTelegramIdentityLink::telegramUserId).toSet().size == links.size) {
            "duplicate Telegram identity"
        }
        require(
            links.mapNotNull(StoredTelegramIdentityLink::telegramUsername)
                .map { it.lowercase(Locale.ROOT) }
                .let { usernames -> usernames.toSet().size == usernames.size },
        ) { "duplicate Telegram username metadata" }
        links.forEach(StoredTelegramIdentityLink::validate)
        require(challenges.map(StoredTelegramChallenge::id).toSet().size == challenges.size) {
            "duplicate Telegram identity challenge"
        }
        challenges.forEach(StoredTelegramChallenge::validate)
        rates.forEach(StoredTelegramRateWindow::validate)
        audit.forEach(StoredTelegramIdentityAudit::validate)
    }

    fun copyDeep(): StoredTelegramIdentityState =
        StoredTelegramIdentityState().also { copy ->
            copy.schemaVersion = schemaVersion
            copy.links = links.mapTo(mutableListOf(), StoredTelegramIdentityLink::copy)
            copy.challenges = challenges.mapTo(mutableListOf(), StoredTelegramChallenge::copy)
            copy.rates = rates.mapTo(mutableListOf(), StoredTelegramRateWindow::copy)
            copy.audit = audit.mapTo(mutableListOf(), StoredTelegramIdentityAudit::copy)
        }

    companion object {
        private const val MAX_LINKS = 10_000
        private const val MAX_CHALLENGES = 10_000
        private const val MAX_RATES = 20_000
        private const val MAX_AUDIT = 2_000
    }
}

internal data class StoredTelegramIdentityLink(
    var playerUuid: String = "",
    var playerName: String = "",
    var telegramUserId: Long = 0,
    var telegramUsername: String? = null,
    var telegramDisplayName: String = "",
    var linkedAt: Long = 0,
    var updatedAt: Long = 0,
) {
    fun validate() {
        require(runCatching { UUID.fromString(playerUuid) }.isSuccess) { "invalid Telegram identity UUID" }
        require(PLAYER_NAME.matches(playerName)) { "invalid Telegram identity player name" }
        require(telegramUserId > 0) { "invalid Telegram user id" }
        require(telegramUsername == null || USERNAME.matches(telegramUsername!!)) { "invalid Telegram username" }
        require(telegramDisplayName.isNotBlank() && telegramDisplayName.length <= 128) { "invalid Telegram display name" }
        require(linkedAt >= 0 && updatedAt >= linkedAt) { "invalid Telegram identity timestamps" }
    }

    fun toDomain(): TelegramIdentityLink =
        TelegramIdentityLink(
            playerUuid = UUID.fromString(playerUuid),
            playerName = playerName,
            telegramUserId = telegramUserId,
            telegramUsername = telegramUsername,
            telegramDisplayName = telegramDisplayName,
            linkedAt = linkedAt,
            updatedAt = updatedAt,
        )

    companion object {
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        private val USERNAME = Regex("[A-Za-z0-9_]{1,32}")
    }
}

internal data class StoredTelegramChallenge(
    var id: String = "",
    var playerUuid: String = "",
    var playerName: String = "",
    var codeHash: String = "",
    var createdAt: Long = 0,
    var expiresAt: Long = 0,
    var completedTelegramUserId: Long? = null,
    var completedAt: Long = 0,
) {
    fun validate() {
        require(id.isNotBlank() && id.length <= 64) { "invalid Telegram challenge id" }
        require(runCatching { UUID.fromString(playerUuid) }.isSuccess) { "invalid Telegram challenge UUID" }
        require(Regex("[A-Za-z0-9_]{1,16}").matches(playerName)) { "invalid Telegram challenge player name" }
        require(Regex("[0-9a-f]{64}").matches(codeHash)) { "invalid Telegram challenge hash" }
        require(createdAt >= 0 && expiresAt > createdAt) { "invalid Telegram challenge timestamps" }
        require(completedTelegramUserId == null || completedTelegramUserId!! > 0) { "invalid completed Telegram id" }
    }
}

internal data class StoredTelegramRateWindow(
    var key: String = "",
    var windowStartedAt: Long = 0,
    var count: Int = 0,
    var nextAllowedAt: Long = 0,
) {
    fun validate() {
        require(key.isNotBlank() && key.length <= 96) { "invalid Telegram rate key" }
        require(windowStartedAt >= 0 && count >= 0 && nextAllowedAt >= 0) { "invalid Telegram rate window" }
    }
}

internal data class StoredTelegramIdentityAudit(
    var timestamp: Long = 0,
    var event: String = "",
    var outcome: String = "",
    var playerUuid: String? = null,
    var playerName: String? = null,
    var telegramUserId: Long? = null,
) {
    fun validate() {
        require(timestamp >= 0) { "invalid Telegram audit timestamp" }
        require(event.isNotBlank() && outcome.isNotBlank()) { "invalid Telegram audit event" }
        require(telegramUserId == null || telegramUserId!! > 0) { "invalid Telegram audit user" }
    }
}
