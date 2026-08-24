package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class DiscordRoleServiceTest : FreeSpec({
    "desired roles are exact and limited to the configured allowlist" {
        val config = verificationConfig(Files.createTempDirectory("discord-role-policy"))
        val service =
            DiscordRoleService(
                DiscordSession(),
                config,
                DiscordRoleFactsProvider { _, _ -> CompletableFuture.completedFuture(DiscordRoleFacts(emptySet(), emptySet())) },
            )

        service.desiredRoleIds(
            DiscordRoleFacts(
                groups = setOf("vip", "helper", "some-unmanaged-group"),
                permissions = emptySet(),
            ),
        ) shouldContainExactlyInAnyOrder
            setOf(
                config.verifiedRoleId,
                config.playerRoleId,
                "1083480822818029608",
                "1079927708743643156",
            )
    }

    "permission facts can grant VIP without a matching group" {
        val config = verificationConfig(Files.createTempDirectory("discord-role-permission"))
        val service =
            DiscordRoleService(
                DiscordSession(),
                config,
                DiscordRoleFactsProvider { _, _ -> CompletableFuture.completedFuture(DiscordRoleFacts(emptySet(), emptySet())) },
            )

        service.desiredRoleIds(DiscordRoleFacts(emptySet(), setOf("arc.vip"))) shouldContainExactlyInAnyOrder
            setOf(config.verifiedRoleId, config.playerRoleId, "1083480822818029608")
    }
})
