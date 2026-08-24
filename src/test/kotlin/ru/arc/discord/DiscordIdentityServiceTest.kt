package ru.arc.discord

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.util.UUID

class DiscordIdentityServiceTest : FreeSpec({
    val playerOne = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val playerTwo = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val discordOne = "1073279640912789595"
    val discordTwo = "1083092420394221699"

    fun fixture(
        now: Long = 1_000_000L,
        codes: ArrayDeque<String> = ArrayDeque(listOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")),
    ): Triple<DiscordIdentityService, (Long) -> Unit, java.nio.file.Path> {
        val root = Files.createTempDirectory("discord-identities")
        val config = verificationConfig(root)
        val dataPath = root.resolve("data/discord-identities.json")
        var current = now
        val service =
            DiscordIdentityService(
                DiscordIdentityStore(dataPath),
                config,
                clock = { current },
                codeGenerator = { codes.removeFirst() },
                idGenerator = { "challenge-${codes.size}" },
            )
        return Triple(service, { value -> current = value }, dataPath)
    }

    "stores only a code digest and completes a unique link" {
        val (service, _, path) = fixture()
        val issued = service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued

        issued.code shouldBe "ABCD-EFGH"
        Files.readString(path) shouldNotContain "ABCDEFGH"

        val completed = service.completeChallenge(issued.code, discordOne) as DiscordChallengeCompletionResult.Linked
        completed.idempotent shouldBe false
        completed.link.playerUuid shouldBe playerOne
        service.findByDiscordUserId(discordOne)?.playerName shouldBe "PlayerOne"
    }

    "repeating the same completion is idempotent only for the same Discord user" {
        val (service, _, _) = fixture()
        val code = (service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued).code
        service.completeChallenge(code, discordOne) as DiscordChallengeCompletionResult.Linked

        val repeated = service.completeChallenge(code, discordOne) as DiscordChallengeCompletionResult.Linked
        repeated.idempotent shouldBe true
        service.completeChallenge(code, discordTwo) shouldBe DiscordChallengeCompletionResult.InvalidOrExpired
    }

    "forbids linking one Discord account to two Minecraft UUIDs" {
        val (service, _, _) = fixture()
        val first = (service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued).code
        service.completeChallenge(first, discordOne) as DiscordChallengeCompletionResult.Linked
        val second = (service.issueLinkChallenge(playerTwo, "PlayerTwo") as DiscordChallengeIssueResult.Issued).code

        service.completeChallenge(second, discordOne) shouldBe DiscordChallengeCompletionResult.DiscordAlreadyLinked
        service.findByPlayerUuid(playerTwo) shouldBe null
    }

    "regenerates a colliding active code instead of resolving the wrong player" {
        val codes = ArrayDeque(listOf("ABCDEFGH", "ABCDEFGH", "JKLMNPQR"))
        val (service, _, _) = fixture(codes = codes)
        val first = service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued
        val second = service.issueLinkChallenge(playerTwo, "PlayerTwo") as DiscordChallengeIssueResult.Issued

        first.code shouldBe "ABCD-EFGH"
        second.code shouldBe "JKLM-NPQR"
        (service.completeChallenge(second.code, discordTwo) as DiscordChallengeCompletionResult.Linked)
            .link.playerUuid shouldBe playerTwo
    }

    "recovery requires the explicit recovery challenge and replaces the snowflake" {
        val (service, setNow, _) = fixture()
        val linkCode = (service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued).code
        service.completeChallenge(linkCode, discordOne) as DiscordChallengeCompletionResult.Linked
        setNow(1_061_000L)
        val recoveryCode =
            (service.issueRecoveryChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued).code
        val prepared =
            service.completeChallenge(recoveryCode, discordTwo) as DiscordChallengeCompletionResult.RecoveryPrepared

        val recovered =
            service.completeRecovery(prepared.challengeId, discordTwo) as DiscordRecoveryCompletionResult.Recovered
        recovered.previousLink.discordUserId shouldBe discordOne
        recovered.newLink.discordUserId shouldBe discordTwo
        recovered.idempotent shouldBe false
        service.findByDiscordUserId(discordOne) shouldBe null

        val repeated = service.completeRecovery(prepared.challengeId, discordTwo) as DiscordRecoveryCompletionResult.Recovered
        repeated.idempotent shouldBe true
    }

    "pending challenge survives a store restart" {
        val (service, _, path) = fixture()
        val code = (service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued).code
        val root = path.parent.parent
        val restarted =
            DiscordIdentityService(
                DiscordIdentityStore(path),
                verificationConfig(root),
                clock = { 1_000_100L },
                codeGenerator = { "ZZZZZZZZ" },
            )

        val completed = restarted.completeChallenge(code, discordOne) as DiscordChallengeCompletionResult.Linked
        completed.link.playerUuid shouldBe playerOne
    }

    "unlink removes the identity and its pending challenges" {
        val (service, setNow, _) = fixture()
        val code = (service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued).code
        service.completeChallenge(code, discordOne) as DiscordChallengeCompletionResult.Linked
        setNow(1_061_000L)
        service.issueRecoveryChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued

        service.completeUnlink(playerOne, discordOne) as DiscordUnlinkResult.Unlinked
        service.findByPlayerUuid(playerOne) shouldBe null
        service.completeChallenge("JKLM-NPQR", discordTwo) shouldBe DiscordChallengeCompletionResult.InvalidOrExpired
    }

    "expired challenge fails closed" {
        val (service, setNow, _) = fixture()
        val code = (service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued).code
        setNow(1_700_001L)

        service.completeChallenge(code, discordOne) shouldBe DiscordChallengeCompletionResult.InvalidOrExpired
        service.findByPlayerUuid(playerOne) shouldBe null
    }

    "issue and attempt rate limits are enforced" {
        val codes = ArrayDeque(listOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ", "23456789"))
        val (service, setNow, _) = fixture(codes = codes)
        service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued
        (service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.RateLimited)
            .retryAt shouldBe 1_060_000L
        setNow(1_061_000L)
        service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued
        setNow(1_122_000L)
        service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.Issued
        setNow(1_183_000L)
        service.issueLinkChallenge(playerOne, "PlayerOne") as DiscordChallengeIssueResult.RateLimited

        repeat(5) {
            service.completeChallenge("BAD-CODE", discordOne) shouldBe DiscordChallengeCompletionResult.InvalidOrExpired
        }
        service.completeChallenge("BAD-CODE", discordOne) as DiscordChallengeCompletionResult.RateLimited
    }

    "corrupt snapshot disables mutations instead of resetting identities" {
        val root = Files.createTempDirectory("discord-identities-corrupt")
        val path = root.resolve("data/discord-identities.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, "{not-json")
        val service = DiscordIdentityService(DiscordIdentityStore(path), verificationConfig(root))

        service.isAvailable() shouldBe false
        service.issueLinkChallenge(playerOne, "PlayerOne") shouldBe DiscordChallengeIssueResult.Unavailable
    }

    "duplicate identities in a syntactically valid snapshot fail closed" {
        val root = Files.createTempDirectory("discord-identities-duplicate")
        val path = root.resolve("data/discord-identities.json")
        Files.createDirectories(path.parent)
        val state = DiscordIdentityState()
        state.links += StoredDiscordIdentityLink(playerOne.toString(), "PlayerOne", discordOne, 1, 1)
        state.links += StoredDiscordIdentityLink(playerOne.toString(), "PlayerOne", discordTwo, 1, 1)
        Files.writeString(path, Gson().toJson(state))

        val service = DiscordIdentityService(DiscordIdentityStore(path), verificationConfig(root))

        service.isAvailable() shouldBe false
        service.findByPlayerUuid(playerOne) shouldBe null
    }
})
