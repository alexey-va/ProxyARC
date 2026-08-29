package ru.arc.join

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path

class JoinAnnouncementConfig(
    private val config: Config,
) {
    fun minecraftMessage(announcement: PublishedAnnouncement): String {
        val family = family(announcement.kind)
        val custom = announcement.customMessage?.takeIf(String::isNotBlank)
        val message =
            if (custom != null) {
                prefix(family) + custom
            } else {
                val configuredBody = configuredBody(family)
                val body = configuredBody ?: family.defaultBody
                val configuredPrefix = config.stringOrNull(family.prefixKey)
                when {
                    configuredPrefix != null -> configuredPrefix + body
                    configuredBody != null -> body
                    else -> family.defaultPrefix + body
                }
            }
        return message.replace("%player_name%", announcement.playerName)
    }

    private fun configuredBody(family: MessageFamily): String? =
        config.stringOrNull(family.bodyKey)
            ?: family.legacyBodyKey?.let(config::stringOrNull)

    private fun prefix(family: MessageFamily): String =
        config.stringOrNull(family.prefixKey) ?: family.defaultPrefix

    private fun family(kind: JoinAnnouncementKind): MessageFamily =
        when (kind) {
            JoinAnnouncementKind.FIRST_TIME ->
                MessageFamily(
                    prefixKey = "messages.join-prefix",
                    bodyKey = "messages.first-time",
                    legacyBodyKey = "messages.first-join",
                    defaultPrefix = "<dark_green>● ",
                    defaultBody = "<gray>Игрок <green>%player_name% <gray>впервые на сервере!",
                )

            JoinAnnouncementKind.JOIN ->
                MessageFamily(
                    prefixKey = "messages.join-prefix",
                    bodyKey = "messages.join",
                    defaultPrefix = "<dark_green>● ",
                    defaultBody = "<gray>Игрок <green>%player_name% <gray>присоединился!",
                )

            JoinAnnouncementKind.LEAVE ->
                MessageFamily(
                    prefixKey = "messages.leave-prefix",
                    bodyKey = "messages.leave",
                    defaultPrefix = "<dark_red>● ",
                    defaultBody = "<gray>Игрок <red>%player_name% <gray>вышел!",
                )
        }

    private data class MessageFamily(
        val prefixKey: String,
        val bodyKey: String,
        val legacyBodyKey: String? = null,
        val defaultPrefix: String,
        val defaultBody: String,
    )

    companion object {
        private const val RESOURCE = "join-messages.yml"
        private const val LEGACY_RESOURCE = "join_config.yml"

        fun load(dataRoot: Path): JoinAnnouncementConfig {
            val alreadySeparated =
                Files.exists(ConfigManager.moduleYamlPath(dataRoot, RESOURCE)) ||
                    Files.exists(dataRoot.resolve(RESOURCE))
            val target = ConfigManager.ofModule(dataRoot, RESOURCE)
            if (!alreadySeparated) {
                migrateLegacy(ConfigManager.ofModule(dataRoot, LEGACY_RESOURCE), target)
            }
            return JoinAnnouncementConfig(target)
        }

        private fun migrateLegacy(legacy: Config, target: Config) {
            val firstTime =
                legacy.stringOrNull("messages.first-time")
                    ?: legacy.stringOrNull("messages.first-join")
            val legacyJoin = legacy.stringOrNull("messages.join")
            val legacyLeave = legacy.stringOrNull("messages.leave")
            val knownSwappedDefaults =
                legacyJoin?.contains("вышел", ignoreCase = true) == true &&
                    legacyLeave?.contains("присоединился", ignoreCase = true) == true
            val join = if (knownSwappedDefaults) legacyLeave else legacyJoin
            val leave = if (knownSwappedDefaults) legacyJoin else legacyLeave
            if (firstTime == null && join == null && leave == null) return

            firstTime?.let { target.setString("messages.first-time", it) }
            join?.let { target.setString("messages.join", it) }
            leave?.let { target.setString("messages.leave", it) }
            target.setString(
                "messages.join-prefix",
                legacy.stringOrNull("messages.join-prefix").orEmpty(),
            )
            target.setString(
                "messages.leave-prefix",
                legacy.stringOrNull("messages.leave-prefix").orEmpty(),
            )
            target.saveStrict()
        }
    }
}
