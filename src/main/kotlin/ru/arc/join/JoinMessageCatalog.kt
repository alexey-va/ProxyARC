package ru.arc.join

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ThreadLocalRandom

data class JoinMessageCatalogEntry(
    @JvmField var id: String = "",
    @JvmField var message: String = "",
    @JvmField var displayName: String = "",
    @JvmField var material: String = "PAPER",
    @JvmField var customModelData: Int = 0,
    @JvmField var permission: String? = null,
    @JvmField var rank: String = "<italic:false><green>Для всех",
)

class JoinMessageCatalog(
    @JvmField var catalogId: String = CATALOG_ID,
    @JvmField var schemaVersion: Int = SCHEMA_VERSION,
    @JvmField var revision: String = "",
    @JvmField var updatedAt: Long = 0,
    @JvmField var join: List<JoinMessageCatalogEntry> = emptyList(),
    @JvmField var leave: List<JoinMessageCatalogEntry> = emptyList(),
) : Entity,
    Mergeable<JoinMessageCatalog> {
    override fun id(): String = catalogId

    @Synchronized
    override fun merge(other: JoinMessageCatalog) {
        schemaVersion = other.schemaVersion
        revision = other.revision
        updatedAt = other.updatedAt
        join = other.join.map(JoinMessageCatalogEntry::copy)
        leave = other.leave.map(JoinMessageCatalogEntry::copy)
    }

    fun randomSelectedMessage(
        kind: JoinAnnouncementKind,
        selectedMessages: Set<String>,
    ): String? {
        if (kind == JoinAnnouncementKind.FIRST_TIME || selectedMessages.isEmpty()) return null
        val entries = if (kind == JoinAnnouncementKind.JOIN) join else leave
        val allowed = entries.map(JoinMessageCatalogEntry::message).filter(selectedMessages::contains)
        if (allowed.isEmpty()) return null
        return allowed[ThreadLocalRandom.current().nextInt(allowed.size)]
    }

    companion object {
        const val CATALOG_ID = "catalog"
        const val SCHEMA_VERSION = 1
    }
}

class JoinMessageCatalogConfig(
    private val config: Config,
) {
    fun snapshot(updatedAt: Long = System.currentTimeMillis()): JoinMessageCatalog {
        val join = entries("catalog.join")
        val leave = entries("catalog.leave")
        require(join.isNotEmpty()) { "Join message catalog must contain at least one join phrase" }
        require(leave.isNotEmpty()) { "Join message catalog must contain at least one leave phrase" }
        require(join.size <= MAX_ENTRIES_PER_KIND && leave.size <= MAX_ENTRIES_PER_KIND) {
            "Join message catalog may contain at most $MAX_ENTRIES_PER_KIND phrases per kind"
        }
        val duplicateIds = (join + leave).groupingBy(JoinMessageCatalogEntry::id).eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) { "Join message catalog contains duplicate ids: ${duplicateIds.sorted()}" }

        return JoinMessageCatalog(
            revision = revision(join, leave),
            updatedAt = updatedAt,
            join = join,
            leave = leave,
        )
    }

    private fun entries(root: String): List<JoinMessageCatalogEntry> =
        config.keys(root)
            .map { id -> config.int("$root.$id.order", Int.MAX_VALUE) to entry(root, id) }
            .sortedWith(compareBy<Pair<Int, JoinMessageCatalogEntry>> { it.first }.thenBy { it.second.id })
            .map(Pair<Int, JoinMessageCatalogEntry>::second)

    private fun entry(
        root: String,
        id: String,
    ): JoinMessageCatalogEntry {
        require(SAFE_ID.matches(id)) { "Invalid join message id: $id" }
        val path = "$root.$id"
        val message = config.stringOrNull("$path.message")?.trim().orEmpty()
        require(message.isNotEmpty() && message.length <= MAX_MESSAGE_LENGTH) {
            "Join message '$id' must contain 1..$MAX_MESSAGE_LENGTH characters"
        }
        val material = config.stringOrNull("$path.material")?.trim()?.uppercase().orEmpty()
        require(MATERIAL_NAME.matches(material)) { "Invalid material name for join message '$id': $material" }
        val displayName = nonItalic(
            config.stringOrNull("$path.display-name")?.trim().orEmpty().ifBlank { "<gold>Сообщение $id" },
        )
        val rank = nonItalic(
            config.stringOrNull("$path.rank")?.trim().orEmpty().ifBlank { "<green>Для всех" },
        )
        val customModelData = config.int("$path.custom-model-data", 0)
        require(customModelData >= 0) { "custom-model-data for join message '$id' must not be negative" }
        return JoinMessageCatalogEntry(
            id = id,
            message = message,
            displayName = displayName,
            material = material,
            customModelData = customModelData,
            permission = config.stringOrNull("$path.permission")?.trim()?.takeIf(String::isNotEmpty),
            rank = rank,
        )
    }

    private fun revision(
        join: List<JoinMessageCatalogEntry>,
        leave: List<JoinMessageCatalogEntry>,
    ): String {
        val canonical = buildString {
            append(JoinMessageCatalog.SCHEMA_VERSION).append('\n')
            appendEntries("join", join)
            appendEntries("leave", leave)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun StringBuilder.appendEntries(
        kind: String,
        entries: List<JoinMessageCatalogEntry>,
    ) {
        entries.forEach { entry ->
            append(kind).append('\u0000')
            append(entry.id).append('\u0000')
            append(entry.message).append('\u0000')
            append(entry.displayName).append('\u0000')
            append(entry.material).append('\u0000')
            append(entry.customModelData).append('\u0000')
            append(entry.permission.orEmpty()).append('\u0000')
            append(entry.rank).append('\n')
        }
    }

    companion object {
        private const val RESOURCE = "join-messages.yml"
        private const val MAX_ENTRIES_PER_KIND = 100
        private const val MAX_MESSAGE_LENGTH = 512
        private val SAFE_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val MATERIAL_NAME = Regex("[A-Z][A-Z0-9_]{0,63}")

        fun load(dataRoot: Path): JoinMessageCatalogConfig {
            // This module starts before the listener. Migrate the legacy announcement
            // section before the shared file is created for the catalog.
            JoinAnnouncementConfig.load(dataRoot)
            val config = ConfigManager.ofModule(dataRoot, RESOURCE)
            if (!config.exists("catalog")) addBundledCatalog(config)
            return JoinMessageCatalogConfig(config)
        }

        private fun addBundledCatalog(config: Config) {
            val temporaryRoot = Files.createTempDirectory("proxyarc-join-catalog-defaults-")
            val resource = ConfigManager.bundledModuleResource(RESOURCE)
            try {
                Config.copyDefaultConfig(resource, temporaryRoot, replace = false)
                val defaults = Config(temporaryRoot, resource)
                val catalog = defaults.map<Any?>("catalog")
                require(catalog.isNotEmpty()) { "Bundled join message catalog is missing" }
                config.setStructured("catalog", catalog)
                config.saveStrict()
            } finally {
                Files.deleteIfExists(temporaryRoot.resolve(resource))
                Files.deleteIfExists(temporaryRoot.resolve(ConfigManager.MODULE_YAML_DIR))
                Files.deleteIfExists(temporaryRoot)
            }
        }

        private fun nonItalic(value: String): String =
            if (value.startsWith("<italic:")) value else "<italic:false>$value"
    }
}

internal class JoinMessageCatalogPublication(
    private val current: () -> JoinMessageCatalog?,
    private val persist: suspend (JoinMessageCatalog) -> Unit,
) {
    suspend fun publish(snapshot: JoinMessageCatalog): Boolean {
        if (current()?.revision == snapshot.revision) return false
        persist(snapshot)
        return true
    }
}
