package ru.arc.telegram

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import ru.arc.config.ProxyConfigs
import java.net.URI
import java.nio.file.Path
import java.util.Locale

data class TelegramDestination(
    val chatId: String,
    val threadId: Int? = null,
)

open class TelegramConfig(
    private val config: Config,
    private val legacyCredentials: Config = EmptyConfig,
) {
    open val enabled: Boolean
        get() = config.bool("enabled", false)

    open val token: String
        get() {
            val configured = config.string("token", "none").trim()
            return configured.takeUnless { it.isBlank() || it == "none" }
                ?: legacyCredentials.string("token", "none").trim()
        }

    open val botUsername: String
        get() = config.string("username", "RusCrafting").trim()

    open val chatDestination: TelegramDestination?
        get() = destination("bridge.chat", "topics.chat")

    open val generalDestination: TelegramDestination?
        get() = destination("bridge.general", "topics.general")

    open val informationDestination: TelegramDestination?
        get() {
            val chatId = config.string("channels.information.chat-id", "").trim()
            if (!TelegramChatIds.isValid(chatId)) return null
            val threadId = config.integer("channels.information.thread-id", 0).takeIf { it > 0 }
            return TelegramDestination(chatId, threadId)
        }

    open val mirrorGeneralToInformation: Boolean
        get() = config.bool("channels.information.mirror-general", true)

    open val chatFormat: String
        get() = config.string("chat-format", "<white></white> <dark_gray>| <gray>%sender% <dark_gray>» <white>%message%")

    open val discordFormat: String
        get() = config.string("discord-format", "**%sender%** » %message%")

    open val identityEnabled: Boolean
        get() = config.bool("identity.enabled", true)

    open val identityPrivateChatOnly: Boolean
        get() = config.bool("identity.private-chat-only", true)

    open val identityAllowedBackends: Set<String>
        get() =
            config.stringList("identity.allowed-backends")
                .map { it.trim().lowercase() }
                .filter(String::isNotBlank)
                .toSet()

    open val identityCodeLength: Int
        get() = config.integer("identity.codes.length", 8).coerceIn(6, 16)

    open val identityCodeTtlSeconds: Int
        get() = config.integer("identity.codes.ttl-seconds", 600).coerceIn(60, 3_600)

    open val identityIssueCooldownSeconds: Int
        get() = config.integer("identity.codes.issue-cooldown-seconds", 60).coerceIn(1, 3_600)

    open val identityIssueWindowSeconds: Int
        get() = config.integer("identity.codes.issue-window-seconds", 600).coerceIn(60, 86_400)

    open val identityMaxIssuesPerWindow: Int
        get() = config.integer("identity.codes.max-issues-per-window", 3).coerceIn(1, 20)

    open val identityAttemptWindowSeconds: Int
        get() = config.integer("identity.codes.attempt-window-seconds", 600).coerceIn(60, 86_400)

    open val identityMaxAttemptsPerWindow: Int
        get() = config.integer("identity.codes.max-attempts-per-window", 5).coerceIn(1, 50)

    open fun identityMessage(
        key: String,
        values: Map<String, String> = emptyMap(),
    ): String {
        var message = config.string("identity.messages.$key", DEFAULT_IDENTITY_MESSAGES.getValue(key))
        values.forEach { (name, value) -> message = message.replace("%$name%", value) }
        return message
    }

    internal fun minecraftIdentityString(path: String): String =
        config.string("identity.minecraft.$path", "")

    internal fun minecraftIdentityLines(path: String): List<String> =
        config.stringList("identity.minecraft.$path")

    open fun joinMessage(kind: String): String =
        config.string("messages.$kind", DEFAULT_JOIN_MESSAGES.getValue(kind))

    fun generalDestinations(): List<TelegramDestination> =
        buildList {
            generalDestination?.let(::add)
            if (mirrorGeneralToInformation) informationDestination?.let(::add)
        }.distinct()

    fun validate() {
        if (!enabled) return
        require(token.isNotBlank() && token != "none") { "telegram token is required when enabled" }
        require(botUsername.isNotBlank()) { "telegram username must not be blank" }
        if (identityEnabled) {
            require(identityAllowedBackends.isNotEmpty()) { "Telegram identity allowed backends must not be empty" }
            TelegramVerificationMessages(this).validate()
        }
    }

    private fun destination(
        path: String,
        legacyThreadPath: String,
    ): TelegramDestination? {
        val legacyChatId = config.longValue("chat-id", 0L).takeIf { it != 0L }?.toString().orEmpty()
        val configuredChatId = config.string("$path.chat-id", legacyChatId).trim()
        val chatId = configuredChatId.takeIf(TelegramChatIds::isValid) ?: legacyChatId
        if (!TelegramChatIds.isValid(chatId)) return null
        val legacyThreadId = config.integer(legacyThreadPath, 0)
        val configuredThreadId = config.integer("$path.thread-id", legacyThreadId)
        val threadId = configuredThreadId.takeIf { it > 0 } ?: legacyThreadId.takeIf { it > 0 }
        return TelegramDestination(chatId, threadId)
    }

    companion object {
        private val DEFAULT_JOIN_MESSAGES =
            mapOf(
                "join" to "Игрок %player_name% присоединился к серверу",
                "first_time" to "Игрок %player_name% впервые присоединился к серверу",
                "leave" to "Игрок %player_name% покинул сервер",
            )
        private val DEFAULT_IDENTITY_MESSAGES =
            mapOf(
                "minecraft-challenge" to
                    "Код Telegram: %code%. Откройте @%bot_username% в личных сообщениях и отправьте /verify %code%. Код действует %minutes% мин.",
                "minecraft-telegram-already-linked" to "Telegram уже привязан к %player_name%.",
                "minecraft-rate-limited" to "Новый код Telegram можно получить через %minutes% мин.",
                "minecraft-unavailable" to "Привязка Telegram временно недоступна.",
                "minecraft-status-linked" to "Telegram привязан: @%telegram_username% → %player_name%.",
                "minecraft-status-not-linked" to "Telegram не привязан.",
                "minecraft-unlink-success" to "Telegram-аккаунт отвязан.",
                "minecraft-unlink-not-linked" to "Telegram-аккаунт не был привязан.",
                "private-only" to "Команды привязки работают только в личном чате с ботом.",
                "status-linked" to "Привязан Minecraft-аккаунт: %player_name%.",
                "status-not-linked" to "Аккаунт не привязан. Получите код в игре: /verify telegram",
                "verified" to "Telegram привязан к Minecraft-аккаунту %player_name%.",
                "verified-idempotent" to "Telegram уже привязан к Minecraft-аккаунту %player_name%.",
                "invalid-or-expired" to "Код неверный или истёк. Получите новый: /verify telegram",
                "minecraft-already-linked" to "Этот Minecraft-аккаунт уже связан с другим Telegram.",
                "telegram-already-linked" to "Этот Telegram уже связан с другим Minecraft-аккаунтом.",
                "rate-limited" to "Слишком много попыток. Повторите через %minutes% мин.",
                "unlink-success" to "Telegram отвязан от Minecraft-аккаунта %player_name%.",
                "unlink-not-linked" to "Telegram не привязан.",
                "unlink-confirm" to "Для отвязки отправьте /unlink confirm",
                "usage" to "Использование: /verify <код>, /verify, /unlink confirm",
            )

        fun load(): TelegramConfig = load(ProxyConfigs.dataRoot())

        fun load(dataRoot: Path): TelegramConfig {
            val config = ConfigManager.ofModule(dataRoot, "telegram.yml")
            return TelegramConfig(
                config = config,
                legacyCredentials = ConfigManager.of(dataRoot, "telegram.yml"),
            )
        }

        internal fun botUrl(username: String): String? {
            val normalized = username.removePrefix("@").trim()
            if (!BOT_USERNAME.matches(normalized)) return null
            val uri = runCatching { URI("https://t.me/$normalized") }.getOrNull() ?: return null
            return if (uri.scheme?.lowercase(Locale.ROOT) == "https" && uri.host == "t.me") uri.toString() else null
        }

        private val BOT_USERNAME = Regex("[A-Za-z0-9_]{5,32}")
    }
}

class TestTelegramConfig(
    override val enabled: Boolean = true,
    override val token: String = "test-token",
    override val botUsername: String = "RusCraftingTest",
    override val chatDestination: TelegramDestination? = null,
    override val generalDestination: TelegramDestination? = null,
    override val informationDestination: TelegramDestination? = null,
    override val mirrorGeneralToInformation: Boolean = true,
    override val chatFormat: String = "<white></white> <dark_gray>| <gray>%sender% <dark_gray>» <white>%message%",
    override val discordFormat: String = "**%sender%** » %message%",
    override val identityEnabled: Boolean = false,
    override val identityPrivateChatOnly: Boolean = true,
    override val identityAllowedBackends: Set<String> = setOf("survival"),
    override val identityCodeLength: Int = 8,
    override val identityCodeTtlSeconds: Int = 600,
    override val identityIssueCooldownSeconds: Int = 60,
    override val identityIssueWindowSeconds: Int = 600,
    override val identityMaxIssuesPerWindow: Int = 3,
    override val identityAttemptWindowSeconds: Int = 600,
    override val identityMaxAttemptsPerWindow: Int = 5,
    private val joinMessages: Map<String, String> = emptyMap(),
    private val identityMessages: Map<String, String> = emptyMap(),
) : TelegramConfig(EmptyConfig) {
    override fun joinMessage(kind: String): String = joinMessages[kind] ?: super.joinMessage(kind)

    override fun identityMessage(
        key: String,
        values: Map<String, String>,
    ): String =
        identityMessages[key]?.let { pattern ->
            values.entries.fold(pattern) { current, (name, value) -> current.replace("%$name%", value) }
        } ?: super.identityMessage(key, values)
}

object TelegramChatIds {
    fun isValid(value: String): Boolean {
        val parsed = value.toLongOrNull() ?: return false
        return parsed != 0L && parsed.toString() == value
    }
}
