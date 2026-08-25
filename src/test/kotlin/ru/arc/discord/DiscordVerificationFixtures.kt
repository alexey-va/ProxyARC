package ru.arc.discord

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.ProxyConfigs
import java.nio.file.Path

internal fun verificationConfig(
    root: Path,
    helperRoleId: String = "1079927708743643156",
    allowedBackends: String = "[\"spawn\", \"survival\"]",
    inviteUrl: String = "https://discord.gg/TJUXMGJD9q",
    messageOverrides: Map<String, String> = emptyMap(),
    configure: (Config) -> Unit = {},
): DiscordVerificationConfig {
    ConfigManager.clear()
    ProxyConfigs.module(root, "discord-verification.yml").also { config ->
        config.setBoolean("enabled", true)
        config.setString("guild-id", "1073279640912789595")
        config.setStringList("allowed-backends", parseList(allowedBackends))
        config.setString("roles.verified-role-id", "1083092420394221699")
        config.setString("roles.player-role-id", "1079926438804848660")
        config.setString("roles.policies.vip.role-id", "1083480822818029608")
        config.setString("roles.policies.administration.role-id", "1079927120614146138")
        config.setString("roles.policies.helper.role-id", helperRoleId)
        config.setString("messages.invite-url", inviteUrl)
        messageOverrides.forEach(config::setString)
        configure(config)
        config.saveStrict()
    }
    ConfigManager.clear()
    return DiscordVerificationConfig.load(root).also(DiscordVerificationConfig::validate)
}

private fun parseList(value: String): List<String> =
    value.removePrefix("[").removeSuffix("]")
        .split(',')
        .map { it.trim().removeSurrounding("\"") }
        .filter(String::isNotEmpty)
