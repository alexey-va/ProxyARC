package ru.arc.discord

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class DiscordVerificationConfigTest : FreeSpec({
    "loads exact production-shaped role policy" {
        val root = Files.createTempDirectory("discord-verification-config")
        val config = verificationConfig(root)

        config.allowedBackends shouldContainExactlyInAnyOrder setOf("spawn", "survival")
        config.managedRoleIds shouldContainExactlyInAnyOrder
            setOf(
                "1083092420394221699",
                "1079926438804848660",
                "1083480822818029608",
                "1079927120614146138",
                "1079927708743643156",
            )
        config.nickname("PlayerName") shouldBe "PlayerName"
        config.inviteUrl shouldBe "https://discord.gg/TJUXMGJD9q"
    }

    "rejects duplicate managed role ids" {
        val root = Files.createTempDirectory("discord-verification-duplicate-role")
        shouldThrow<IllegalArgumentException> {
            verificationConfig(
                root,
                helperRoleId = "1083092420394221699",
            )
        }
    }

    "rejects a missing managed policy role instead of abandoning stale roles" {
        val root = Files.createTempDirectory("discord-verification-missing-role")
        shouldThrow<IllegalArgumentException> {
            verificationConfig(root, helperRoleId = "none")
        }
    }

    "rejects an authentication backend in the verification allowlist" {
        val root = Files.createTempDirectory("discord-verification-auth-backend")
        shouldThrow<IllegalArgumentException> {
            verificationConfig(root, allowedBackends = "[\"spawn\", \"limboauth\"]")
        }
    }

    "rejects a Discord-looking open redirect instead of exposing it as a chat link" {
        val root = Files.createTempDirectory("discord-verification-invite-url")
        shouldThrow<IllegalArgumentException> {
            verificationConfig(root, inviteUrl = "https://discord.gg.evil.example/TJUXMGJD9q")
        }
    }

    "accepts only direct HTTPS Discord invitation targets" {
        DiscordVerificationConfig.validInviteUrl("https://discord.gg/TJUXMGJD9q") shouldBe true
        DiscordVerificationConfig.validInviteUrl("https://discord.com/invite/TJUXMGJD9q") shouldBe true
        listOf(
            "http://discord.gg/TJUXMGJD9q",
            "https://user@discord.gg/TJUXMGJD9q",
            "https://discord.gg:443/TJUXMGJD9q",
            "https://discord.gg/TJUXMGJD9q?next=https://evil.example",
            "https://discord.gg/TJUXMGJD9q#fragment",
            "https://discord.gg/TJUXMGJD9q/extra",
        ).forEach { candidate ->
            DiscordVerificationConfig.validInviteUrl(candidate) shouldBe false
        }
    }
})
