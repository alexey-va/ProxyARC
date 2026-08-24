package ru.arc.discord

import ru.arc.config.Config
import ru.arc.config.ProxyConfigs
import java.nio.file.Path
import java.util.Locale

internal data class DiscordRolePolicyRule(
    val name: String,
    val roleId: String,
    val groups: Set<String>,
    val permissions: Set<String>,
) {
    fun matches(facts: DiscordRoleFacts): Boolean =
        groups.any(facts.groups::contains) || permissions.any(facts.permissions::contains)
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
    val messageIdentity: String get() = config.string("messages.identity", "Discord").trim().take(24)

    val policyRules: List<DiscordRolePolicyRule>
        get() = POLICY_NAMES.mapNotNull(::policyRule)

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
        require(nicknameFormat.contains("%player_name%")) {
            "roles.nickname-format must contain %player_name%"
        }
        require(nicknameFormat.length <= 64) { "roles.nickname-format must not exceed 64 characters" }
        if (!enabled) return

        require(validSnowflake(guildId)) { "guild-id must be a Discord snowflake" }
        require(validSnowflake(verifiedRoleId)) { "roles.verified-role-id must be a Discord snowflake" }
        require(validSnowflake(playerRoleId)) { "roles.player-role-id must be a Discord snowflake" }
        require(allowedBackends.isNotEmpty()) { "allowed-backends must not be empty" }
        require(allowedBackends.none(DENIED_AUTH_BACKENDS::contains)) {
            "allowed-backends must not contain an authentication backend"
        }
        require(messageIdentity.isNotBlank()) { "messages.identity must not be blank" }
        require(policyRules.size == POLICY_NAMES.size) {
            "all role policies must configure a valid role id"
        }
        require(managedRoleIds.size == 2 + policyRules.size) {
            "all managed Discord role ids must be distinct"
        }
    }

    fun nickname(playerName: String): String =
        nicknameFormat.replace("%player_name%", playerName).trim().take(32)

    private fun policyRule(name: String): DiscordRolePolicyRule? {
        val root = "roles.policies.$name"
        val roleId = config.string("$root.role-id", "none").trim()
        if (!validSnowflake(roleId)) return null
        val groups = normalizedSet(config.stringList("$root.groups"))
        val permissions = normalizedSet(config.stringList("$root.permissions"))
        require(groups.isNotEmpty() || permissions.isNotEmpty()) {
            "$root must configure groups or permissions"
        }
        return DiscordRolePolicyRule(name, roleId, groups, permissions)
    }

    companion object {
        private val POLICY_NAMES = listOf("vip", "administration", "helper")
        private val DENIED_AUTH_BACKENDS = setOf("auth", "limbo", "limboauth", "login", "prelogin")
        private val SNOWFLAKE = Regex("\\d{17,20}")

        fun load(): DiscordVerificationConfig =
            DiscordVerificationConfig(ProxyConfigs.module("discord-verification.yml"))

        fun load(dataRoot: Path): DiscordVerificationConfig =
            DiscordVerificationConfig(ProxyConfigs.module(dataRoot, "discord-verification.yml"))

        internal fun validSnowflake(value: String): Boolean = SNOWFLAKE.matches(value)

        private fun normalizedSet(values: Collection<String>): Set<String> =
            values.asSequence()
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter(String::isNotEmpty)
                .toCollection(linkedSetOf())
    }
}
