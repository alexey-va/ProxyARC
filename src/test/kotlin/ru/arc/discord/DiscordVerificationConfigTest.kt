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
})
