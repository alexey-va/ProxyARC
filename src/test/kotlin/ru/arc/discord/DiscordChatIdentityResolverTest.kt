package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class DiscordChatIdentityResolverTest : FreeSpec({
    "uses the linked Minecraft name for a verified Discord account" {
        val resolver =
            DiscordChatIdentityResolver { discordUserId ->
                if (discordUserId == "1073279640912789595") "GrocerMC" else null
            }

        resolver.resolve("1073279640912789595", "Different Discord Name") shouldBe "GrocerMC"
    }

    "keeps the Discord display name for an unverified account" {
        val resolver = DiscordChatIdentityResolver()

        resolver.resolve("999999999999999999", "Discord Guest") shouldBe "Discord Guest"
    }

    "does not replace the fallback with a blank identity value" {
        val resolver = DiscordChatIdentityResolver { "" }

        resolver.resolve("1073279640912789595", "Discord Fallback") shouldBe "Discord Fallback"
    }

})
