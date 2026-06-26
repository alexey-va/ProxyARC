package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class DiscordMessageTextTest : FreeSpec({
    "DiscordMessageText" - {
        "should replace user mention with @name" {
            DiscordMessageText.formatContent(
                raw = "hey <@123456789012345678> how are you",
                userNamesById = mapOf("123456789012345678" to "addscoren"),
            ) shouldBe "hey @addscoren how are you"
        }

        "should replace legacy nickname mention" {
            DiscordMessageText.formatContent(
                raw = "<@!987654321098765432> test",
                userNamesById = mapOf("987654321098765432" to "grocermc"),
            ) shouldBe "@grocermc test"
        }

        "should replace role and channel mentions" {
            DiscordMessageText.formatContent(
                raw = "<@&111> look at <#222>",
                roleNamesById = mapOf("111" to "vip"),
                channelNamesById = mapOf("222" to "global"),
            ) shouldBe "@vip look at #global"
        }

        "should simplify custom emoji markup" {
            DiscordMessageText.formatContent(
                raw = "lol <:kekw:1234567890>",
            ) shouldBe "lol :kekw:"
        }
    }
})
