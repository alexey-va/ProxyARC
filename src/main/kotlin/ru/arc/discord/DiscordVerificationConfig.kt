package ru.arc.discord

import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import java.net.URI
import java.nio.file.Path
import java.util.Locale

internal data class DiscordRolePolicyRule(
    val name: String,
    val roleId: String,
    val enabled: Boolean,
    val groups: Set<String>,
    val permissions: Set<String>,
) {
    fun matches(facts: DiscordRoleFacts): Boolean =
        enabled && (groups.any(facts.groups::contains) || permissions.any(facts.permissions::contains))
}

internal data class DiscordRoleFacts(
    val groups: Set<String>,
    val permissions: Set<String>,
)

internal class DiscordVerificationConfig(
    private val config: Config,
) {
    val enabled: Boolean get() = config.bool("enabled", false)
    val guildId: String get() = config.string("guild-id", "none").trim()

    val codeLength: Int get() = config.integer("codes.length", 8)
    val codeTtlSeconds: Long get() = config.longValue("codes.ttl-seconds", 600L)
    val issueCooldownSeconds: Long get() = config.longValue("codes.issue-cooldown-seconds", 60L)
    val issueWindowSeconds: Long get() = config.longValue("codes.issue-window-seconds", 600L)
    val maxIssuesPerWindow: Int get() = config.integer("codes.max-issues-per-window", 3)
    val attemptWindowSeconds: Long get() = config.longValue("codes.attempt-window-seconds", 600L)
    val maxAttemptsPerWindow: Int get() = config.integer("codes.max-attempts-per-window", 5)

    val allowedBackends: Set<String>
        get() = normalizedSet(config.stringList("allowed-backends"))

    val verifiedRoleId: String get() = config.string("roles.verified-role-id", "none").trim()
    val playerRoleId: String get() = config.string("roles.player-role-id", "none").trim()
    val nicknameEnabled: Boolean get() = config.bool("roles.nickname-enabled", true)
    val nicknameFormat: String get() = config.string("roles.nickname-format", "%player_name%").trim()
    val syncIntervalSeconds: Long get() = config.longValue("sync.interval-seconds", 300L)
    val luckPermsEventsEnabled: Boolean get() = config.bool("sync.luckperms-events-enabled", true)
    val eventDebounceMillis: Long get() = config.longValue("sync.event-debounce-millis", 1_000L)
    val eventSuppressionSeconds: Long get() = config.longValue("sync.event-suppression-seconds", 5L)
    val messages = DiscordVerificationMessages(config)
    val messageIdentity: String get() = messages.identity
    val inviteUrl: String get() = messages.inviteUrl

    val policyRules: List<DiscordRolePolicyRule>
        get() = policyNames().map(::policyRule)

    val managedRoleIds: Set<String>
        get() = buildSet {
            verifiedRoleId.takeIf(::validSnowflake)?.let(::add)
            playerRoleId.takeIf(::validSnowflake)?.let(::add)
            policyRules.mapTo(this, DiscordRolePolicyRule::roleId)
        }

    fun validate() {
        require(codeLength in 6..16) { "codes.length must be 6..16" }
        require(codeTtlSeconds in 60..3600) { "codes.ttl-seconds must be 60..3600" }
        require(issueCooldownSeconds in 1..codeTtlSeconds) {
            "codes.issue-cooldown-seconds must be 1..ttl"
        }
        require(issueWindowSeconds in issueCooldownSeconds..86_400) {
            "codes.issue-window-seconds must be cooldown..86400"
        }
        require(maxIssuesPerWindow in 1..20) { "codes.max-issues-per-window must be 1..20" }
        require(attemptWindowSeconds in 60..86_400) {
            "codes.attempt-window-seconds must be 60..86400"
        }
        require(maxAttemptsPerWindow in 1..20) { "codes.max-attempts-per-window must be 1..20" }
        require(syncIntervalSeconds in 60..3600) { "sync.interval-seconds must be 60..3600" }
        require(eventDebounceMillis in 100..10_000) { "sync.event-debounce-millis must be 100..10000" }
        require(eventSuppressionSeconds in 1..60) { "sync.event-suppression-seconds must be 1..60" }
        require(nicknameFormat.contains("%player_name%")) {
            "roles.nickname-format must contain %player_name%"
        }
        require(nicknameFormat.length <= 64) { "roles.nickname-format must not exceed 64 characters" }
        messages.validate(requireInviteUrl = enabled)
        if (!enabled) return

        require(validSnowflake(guildId)) { "guild-id must be a Discord snowflake" }
        require(validSnowflake(verifiedRoleId)) { "roles.verified-role-id must be a Discord snowflake" }
        require(validSnowflake(playerRoleId)) { "roles.player-role-id must be a Discord snowflake" }
        require(allowedBackends.isNotEmpty()) { "allowed-backends must not be empty" }
        require(allowedBackends.none(DENIED_AUTH_BACKENDS::contains)) {
            "allowed-backends must not contain an authentication backend"
        }
        require(policyRules.size <= MAX_POLICY_RULES) {
            "roles.policies must not contain more than $MAX_POLICY_RULES entries"
        }
        policyRules.forEach { rule ->
            require(POLICY_NAME.matches(rule.name)) {
                "roles.policies.${rule.name} must use a lowercase technical name"
            }
            require(validSnowflake(rule.roleId)) {
                "roles.policies.${rule.name}.role-id must be a Discord snowflake"
            }
            require(rule.groups.size <= MAX_POLICY_FACTS && rule.permissions.size <= MAX_POLICY_FACTS) {
                "roles.policies.${rule.name} has too many groups or permissions"
            }
            require(rule.groups.all(GROUP_NAME::matches)) {
                "roles.policies.${rule.name}.groups contains an invalid group name"
            }
            require(rule.permissions.all(PERMISSION_NODE::matches)) {
                "roles.policies.${rule.name}.permissions contains an invalid permission node"
            }
            require(!rule.enabled || rule.groups.isNotEmpty() || rule.permissions.isNotEmpty()) {
                "roles.policies.${rule.name} must configure groups or permissions while enabled"
            }
        }
        require(managedRoleIds.size == 2 + policyRules.size) {
            "all managed Discord role ids must be distinct"
        }
    }

    fun nickname(playerName: String): String =
        nicknameFormat.replace("%player_name%", playerName).trim().take(32)

    private fun policyNames(): List<String> = config.keys("roles.policies").sorted()

    private fun policyRule(name: String): DiscordRolePolicyRule {
        val root = "roles.policies.$name"
        val roleId = config.string("$root.role-id", "none").trim()
        val groups = normalizedSet(config.stringList("$root.groups"))
        val permissions = normalizedSet(config.stringList("$root.permissions"))
        return DiscordRolePolicyRule(
            name = name,
            roleId = roleId,
            enabled = config.bool("$root.enabled", true),
            groups = groups,
            permissions = permissions,
        )
    }

    companion object {
        private val DENIED_AUTH_BACKENDS = setOf("auth", "limbo", "limboauth", "login", "prelogin")
        private val SNOWFLAKE = Regex("\\d{17,20}")
        private val POLICY_NAME = Regex("[a-z0-9][a-z0-9_-]{0,31}")
        private val GROUP_NAME = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val PERMISSION_NODE = Regex("[a-z0-9*][a-z0-9*._-]{0,127}")
        private const val MAX_POLICY_RULES = 64
        private const val MAX_POLICY_FACTS = 64

        fun load(): DiscordVerificationConfig =
            DiscordVerificationConfig(ProxyConfigs.module("discord-verification.yml"))

        fun load(dataRoot: Path): DiscordVerificationConfig =
            DiscordVerificationConfig(ProxyConfigs.module(dataRoot, "discord-verification.yml"))

        internal fun validSnowflake(value: String): Boolean = SNOWFLAKE.matches(value)

        internal fun validInviteUrl(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            if (uri.scheme?.lowercase(Locale.ROOT) != "https" ||
                uri.userInfo != null ||
                uri.port != -1 ||
                uri.rawQuery != null ||
                uri.rawFragment != null
            ) {
                return false
            }
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            val path = uri.rawPath.orEmpty()
            return when (host) {
                "discord.gg" -> DISCORD_INVITE_PATH.matches(path)
                "discord.com", "www.discord.com" -> DISCORD_LONG_INVITE_PATH.matches(path)
                else -> false
            }
        }

        private fun normalizedSet(values: Collection<String>): Set<String> =
            values.asSequence()
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter(String::isNotEmpty)
                .toCollection(linkedSetOf())

        private val DISCORD_INVITE_PATH = Regex("/[A-Za-z0-9_-]{2,64}")
        private val DISCORD_LONG_INVITE_PATH = Regex("/invite/[A-Za-z0-9_-]{2,64}")
    }
}
