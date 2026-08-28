package ru.arc.telegram

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID

class TelegramIdentityServiceTest : FreeSpec({
    "links a stable Telegram user id to a Minecraft UUID and persists only a code digest" {
        val root = Files.createTempDirectory("telegram-identity-")
        val playerUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val service = identityService(root)

        val issued = service.issueChallenge(playerUuid, "PlayerOne") as TelegramChallengeIssueResult.Issued
        issued.code shouldBe "ABCD-EFGH"
        Files.readString(root.resolve("data/telegram-identities.json")).contains("ABCDEFGH") shouldBe false

        val completed =
            service.completeChallenge(
                rawCode = "abcd-efgh",
                telegramUserId = 123456789L,
                telegramUsername = "PlayerTelegram",
                telegramDisplayName = "Player Telegram",
            ) as TelegramChallengeCompletionResult.Linked

        completed.link.playerUuid shouldBe playerUuid
        completed.link.playerName shouldBe "PlayerOne"
        completed.link.telegramUserId shouldBe 123456789L
        completed.idempotent shouldBe false

        val restored = identityService(root)
        restored.findByPlayerUuid(playerUuid) shouldBe completed.link
        restored.findByTelegramUsername("@playertelegram") shouldBe completed.link
    }

    "allows Discord and Telegram to coexist by enforcing uniqueness only inside the Telegram edge" {
        val root = Files.createTempDirectory("telegram-identity-unique-")
        val firstUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val secondUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        var nextCode = "ABCDEFGH"
        val service = identityService(root) { nextCode }

        val first = service.issueChallenge(firstUuid, "PlayerOne") as TelegramChallengeIssueResult.Issued
        service.completeChallenge(first.code, 111L, "one_user", "One") shouldBe
            TelegramChallengeCompletionResult.Linked(
                TelegramIdentityLink(firstUuid, "PlayerOne", 111L, "one_user", "One", 1_000L, 1_000L),
                idempotent = false,
            )

        nextCode = "JKLMNPQR"
        val second = service.issueChallenge(secondUuid, "PlayerTwo") as TelegramChallengeIssueResult.Issued
        service.completeChallenge(second.code, 111L, "one_user", "One") shouldBe
            TelegramChallengeCompletionResult.TelegramAlreadyLinked
        service.findByPlayerUuid(secondUuid) shouldBe null
    }

    "refreshes canonical Minecraft name and mutable Telegram profile independently" {
        val root = Files.createTempDirectory("telegram-identity-profile-")
        val playerUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val service = identityService(root)
        val issued = service.issueChallenge(playerUuid, "OldName") as TelegramChallengeIssueResult.Issued
        service.completeChallenge(issued.code, 222L, "old_user", "Old Display")

        service.updatePlayerName(playerUuid, "NewName")?.playerName shouldBe "NewName"
        val updated = service.updateTelegramProfile(222L, "new_user", "New Display")

        updated?.playerName shouldBe "NewName"
        updated?.telegramUsername shouldBe "new_user"
        updated?.telegramDisplayName shouldBe "New Display"
    }

    "moves reused Telegram username metadata to the currently authenticated user id" {
        val root = Files.createTempDirectory("telegram-identity-username-reuse-")
        val firstUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val secondUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        var nextCode = "ABCDEFGH"
        val service = identityService(root) { nextCode }
        val first = service.issueChallenge(firstUuid, "PlayerOne") as TelegramChallengeIssueResult.Issued
        service.completeChallenge(first.code, 111L, "reused_name", "One")

        nextCode = "JKLMNPQR"
        val second = service.issueChallenge(secondUuid, "PlayerTwo") as TelegramChallengeIssueResult.Issued
        service.completeChallenge(second.code, 222L, "reused_name", "Two")

        service.findByTelegramUserId(111L)?.telegramUsername shouldBe null
        service.findByTelegramUsername("reused_name")?.telegramUserId shouldBe 222L
    }

    "supports confirmed unlink from either authenticated side" {
        val root = Files.createTempDirectory("telegram-identity-unlink-")
        val playerUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val service = identityService(root)
        val issued = service.issueChallenge(playerUuid, "PlayerOne") as TelegramChallengeIssueResult.Issued
        service.completeChallenge(issued.code, 333L, "player_one", "Player One")

        service.unlinkByTelegram(333L) shouldBe
            TelegramUnlinkResult.Unlinked(
                TelegramIdentityLink(playerUuid, "PlayerOne", 333L, "player_one", "Player One", 1_000L, 1_000L),
            )
        service.unlinkByPlayer(playerUuid) shouldBe TelegramUnlinkResult.NotLinked
    }
})

private fun identityService(
    root: java.nio.file.Path,
    codeGenerator: (Int) -> String = { "ABCDEFGH" },
): TelegramIdentityService {
    var challengeSequence = 0
    return TelegramIdentityService(
        store = TelegramIdentityStore(root),
        config =
            TestTelegramConfig(
                identityEnabled = true,
                identityAllowedBackends = setOf("survival"),
                identityIssueCooldownSeconds = 1,
            ),
        clock = { 1_000L },
        codeGenerator = codeGenerator,
        idGenerator = { "challenge-${++challengeSequence}" },
    )
}
