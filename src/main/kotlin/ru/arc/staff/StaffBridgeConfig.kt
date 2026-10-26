package ru.arc.staff

import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import java.net.URI
import java.nio.file.Path
import ru.arc.persistence.AtomicFileStore

internal data class StaffBackend(val baseUrl: URI, val token: String)

internal class StaffBridgeConfig(private val config: Config, private val dataRoot: Path? = null) {
    val enabled = config.bool("enabled", false)
    val adminGroups = config.stringList("permissions.admin-groups", listOf("admin")).map(String::lowercase).toSet()
    val moderatorGroups = config.stringList("permissions.moderator-groups", listOf("moderator")).map(String::lowercase).toSet()
    val guildId = config.string("discord.guild-id", "").trim()
    val commandChannelId = config.string("discord.command-channel-id", "").trim()
    val adminRoleIds = config.stringList("discord.admin-role-ids", emptyList()).toSet()
    val moderatorRoleIds = config.stringList("discord.moderator-role-ids", emptyList()).toSet()
    val commandSpyChannelId = config.string("discord.commandspy-channel-id", "").trim()
    val chatSpyChannelId = config.string("discord.chatspy-channel-id", "").trim()
    val consoleChannels = config.map<Any>("discord.console-channels", emptyMap()).mapValues { it.value.toString() }
    val telegramCommandsEnabled = config.bool("telegram.commands-enabled", true)
    val spiesEnabled = config.bool("spies.enabled", true)
    val allowedOrigins = config.stringList("spies.allowed-origins", listOf("spawn", "survival", "parkour")).toSet()
    val backends = config.map<Any>("backends", emptyMap()).mapValues { (_, raw) ->
        val item = raw as? Map<*, *> ?: error("Invalid staff backend")
        val inline = item["token"]?.toString().orEmpty()
        val file = item["token-file"]?.toString().orEmpty()
        require(inline.isEmpty() || file.isEmpty()) { "Use either a staff backend token or token-file" }
        val token = if (file.isEmpty()) inline else AtomicFileStore(
            requireNotNull(dataRoot), Path.of(file), 4096,
            encode = { value: String -> value.toByteArray(Charsets.UTF_8) },
            decode = { bytes -> bytes.toString(Charsets.UTF_8).trim() },
            validate = { value -> require(value.isNotBlank() && value.none(Char::isISOControl)) },
        ).loadOrNull() ?: error("Staff backend token file missing")
        StaffBackend(URI(item["base-url"].toString()), token)
    }
    val discordDestinations: Set<String> =
        (listOf(commandChannelId, commandSpyChannelId, chatSpyChannelId) + consoleChannels.values).filter(String::isNotEmpty).toSet()

    fun message(key: String): String = config.string("messages.$key", DEFAULT_MESSAGES.getValue(key))

    fun validate(publicDiscordIds: Set<String> = emptySet()) {
        require(adminGroups.isNotEmpty() && adminGroups.intersect(moderatorGroups).isEmpty()) { "Staff groups must be distinct" }
        require((adminGroups + moderatorGroups).all { TOKEN.matches(it) }) { "Invalid staff group" }
        require(allowedOrigins.isNotEmpty() && allowedOrigins.all { TOKEN.matches(it) }) { "Invalid spy origin" }
        require(backends.keys.all { TOKEN.matches(it) && it != "proxy" }) { "Invalid staff backend" }
        backends.values.forEach { backend ->
            require(backend.baseUrl.scheme in setOf("http", "https") && backend.baseUrl.host != null &&
                backend.baseUrl.userInfo == null && backend.baseUrl.query == null && backend.baseUrl.fragment == null &&
                backend.baseUrl.path in setOf("", "/") && backend.token.isNotBlank() && backend.token.none(Char::isISOControl)) {
                "Staff backend requires a base URL and bearer token"
            }
        }
        if (guildId.isNotEmpty()) {
            require(SNOWFLAKE.matches(guildId) && adminRoleIds.isNotEmpty()) { "Discord staff guild and admin role are required" }
            require((discordDestinations + adminRoleIds + moderatorRoleIds).all { SNOWFLAKE.matches(it) }) { "Invalid Discord staff id" }
            val destinations = (listOf(commandChannelId, commandSpyChannelId, chatSpyChannelId) + consoleChannels.values.toSet()).filter(String::isNotEmpty)
            require(destinations.size == destinations.toSet().size) { "Staff destinations must be separate" }
        } else require(discordDestinations.isEmpty()) { "Discord staff destinations require a guild" }
        require(discordDestinations.intersect(publicDiscordIds).isEmpty()) { "Staff Discord destination overlaps public chat or channel sync" }
        require(consoleChannels.keys.all { it == "proxy" || it in allowedOrigins }) { "Unknown console destination" }
    }

    companion object {
        fun load() = StaffBridgeConfig(ProxyConfigs.module("staff-bridge.yml"), ProxyConfigs.dataRoot())
        private val TOKEN = Regex("[a-z0-9][a-z0-9_-]{0,47}")
        private val SNOWFLAKE = Regex("[0-9]{17,20}")
        val DEFAULT_MESSAGES = mapOf(
            "denied" to "Нет доступа. Нужна действующая привязка к игровому аккаунту администратора или модератора.",
            "admin-required" to "Эта команда доступна только администраторам.",
            "unavailable" to "Служебный мост временно недоступен. Команда не отправлена.",
            "unknown-outcome" to "Ответ не получен. Команда могла выполниться; проверьте состояние перед новой попыткой.",
            "duplicate" to "Это событие уже обработано. Повторная команда не отправлена.",
            "stale" to "Сообщение устарело. Отправьте новую команду.",
            "rate-limited" to "Слишком часто. Подождите несколько секунд.",
            "invalid" to "Проверьте команду: /kick <ник> <причина>; /mute или /ban <ник> <10m|2h|7d|permanent> <причина>; /mc unmute/unban <ник>; /mc command <proxy|сервер> <команда>.",
            "help" to "Модерация Minecraft:\n/kick <ник> <причина>\n/ban <ник> <срок> <причина>\n/mute <ник> <срок> <причина>\nСрок: 10m, 2h, 7d или permanent.\n/mc unban <ник> — снять бан\n/mc unmute <ник> — снять мут\n/mc command proxy <команда> — другая команда Velocity\nАдминистраторы могут отправлять команды в любых чатах с ботом. Ответ приходит в личку.\nВ группах указывайте бота: /kick@%bot_username% <ник> <причина>. Сначала откройте личный чат с ботом и нажмите «Старт».\nМодераторы могут писать команды в личку боту, находясь в Minecraft под связанным аккаунтом.",
            "accepted" to "Команда передана. Ответ команды:",
            "no-output" to "Команда передана. Текстовый ответ пока не получен.",
            "not-dispatched" to "Команда не выполнена: нет прав или команда не найдена.",
            "private-only" to "Отправьте служебную команду в личные сообщения боту.",
            "online-required" to "Для команды модератора войдите в Minecraft под связанным аккаунтом.",
            "minecraft-output" to "Подробный ответ команды отправлен в Minecraft.",
        )
    }
}
