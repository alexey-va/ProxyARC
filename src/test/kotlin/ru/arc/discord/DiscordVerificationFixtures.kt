package ru.arc.discord

import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path

internal fun verificationConfig(
    root: Path,
    helperRoleId: String = "1079927708743643156",
    allowedBackends: String = "[\"spawn\", \"survival\"]",
    inviteUrl: String = "https://discord.gg/TJUXMGJD9q",
): DiscordVerificationConfig {
    val modules = root.resolve("modules")
    Files.createDirectories(modules)
    Files.writeString(
        modules.resolve("discord-verification.yml"),
        """
        enabled: true
        guild-id: "1073279640912789595"
        codes:
          length: 8
          ttl-seconds: 600
          issue-cooldown-seconds: 60
          issue-window-seconds: 600
          max-issues-per-window: 3
          attempt-window-seconds: 600
          max-attempts-per-window: 5
        allowed-backends: $allowedBackends
        roles:
          verified-role-id: "1083092420394221699"
          player-role-id: "1079926438804848660"
          nickname-enabled: true
          nickname-format: "%player_name%"
          policies:
            vip:
              role-id: "1083480822818029608"
              groups: ["vip", "supervip"]
              permissions: ["arc.vip"]
            administration:
              role-id: "1079927120614146138"
              groups: ["admin", "moderator"]
              permissions: []
            helper:
              role-id: "$helperRoleId"
              groups: ["helper"]
              permissions: []
        sync:
          interval-seconds: 300
        messages:
          identity: "Discord"
          invite-url: "$inviteUrl"
        """.trimIndent(),
    )
    ConfigManager.clear()
    return DiscordVerificationConfig.load(root).also(DiscordVerificationConfig::validate)
}
