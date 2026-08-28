package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class DiscordIntegrationTextTest : FreeSpec({
    "untrusted Discord text cannot inject markdown or control characters" {
        DiscordTextSafety.markdown("**name**\n@everyone", 40) shouldBe "\\*\\*name\\*\\* @everyone"
    }

    "network signature changes when a player moves server" {
        val spawn = DiscordNetworkSnapshot(listOf(DiscordOnlinePlayer("GrocerMC", "spawn")), setOf("spawn"))
        val survival = DiscordNetworkSnapshot(listOf(DiscordOnlinePlayer("GrocerMC", "survival")), setOf("survival"))

        (DiscordFeedService.networkSignature(spawn) == DiscordFeedService.networkSignature(survival)) shouldBe false
    }

    "join embed omits an unfinished website URL" {
        DiscordFeedService.joinAuthorUrl("") shouldBe null
        DiscordFeedService.joinAuthorUrl("   ") shouldBe null
        DiscordFeedService.joinAuthorUrl("https://example.com") shouldBe "https://example.com"
    }
})
