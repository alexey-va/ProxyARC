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

    "loads arbitrary policy names without a code allowlist" {
        val root = Files.createTempDirectory("discord-verification-dynamic-policy")
        val config =
            verificationConfig(root) { raw ->
                raw.setString("roles.policies.builder.role-id", "123456789012345678")
                raw.setBoolean("roles.policies.builder.enabled", true)
                raw.setStringList("roles.policies.builder.groups", listOf("builder"))
                raw.setStringList("roles.policies.builder.permissions", emptyList())
            }

        config.policyRules.map(DiscordRolePolicyRule::name) shouldBe
            listOf("administration", "builder", "helper", "vip")
        config.managedRoleIds.contains("123456789012345678") shouldBe true
    }

    "keeps a disabled policy managed so reconciliation can remove its role" {
        val root = Files.createTempDirectory("discord-verification-disabled-policy")
        val config =
            verificationConfig(root) { raw ->
                raw.setBoolean("roles.policies.helper.enabled", false)
            }
        val helper = config.policyRules.single { it.name == "helper" }

        helper.matches(DiscordRoleFacts(setOf("helper"), emptySet())) shouldBe false
        config.managedRoleIds.contains(helper.roleId) shouldBe true
    }

    "rejects unsafe policy keys and empty enabled policies" {
        shouldThrow<IllegalArgumentException> {
            verificationConfig(Files.createTempDirectory("discord-verification-policy-name")) { raw ->
                raw.setString("roles.policies.Bad Key.role-id", "123456789012345678")
                raw.setBoolean("roles.policies.Bad Key.enabled", true)
            }
        }
        shouldThrow<IllegalArgumentException> {
            verificationConfig(Files.createTempDirectory("discord-verification-empty-policy")) { raw ->
                raw.setString("roles.policies.empty.role-id", "123456789012345678")
                raw.setBoolean("roles.policies.empty.enabled", true)
                raw.setStringList("roles.policies.empty.groups", emptyList())
                raw.setStringList("roles.policies.empty.permissions", emptyList())
            }
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

    "rejects a missing player-facing message key" {
        val root = Files.createTempDirectory("discord-verification-message-key")
        shouldThrow<IllegalArgumentException> {
            verificationConfig(
                root,
                messageOverrides = mapOf("messages.discord.verified" to ""),
            )
        }
    }
})
