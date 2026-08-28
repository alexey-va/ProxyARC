package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.TextComponent
import java.util.UUID

class DiscordMessageCodecTest : FreeSpec({
    "converts Discord tags without leaking unresolved snowflakes" {
        DiscordMessageCodec.formatDiscordContent(
            "<@111> <@&222> <#333> <:wave:444>",
            userNamesById = mapOf("111" to "Alex"),
        ) shouldBe "@Alex @роль #канал :wave:"
    }

    "converts a linked Minecraft name into a non-pinging Discord mention token" {
        val codec =
            DiscordMessageCodec { name ->
                if (name.equals("PlayerOne", true)) {
                    DiscordIdentityLink(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "PlayerOne",
                        "1073279640912789595",
                        1,
                        1,
                    )
                } else {
                    null
                }
            }

        codec.minecraftToDiscord("Привет @PlayerOne и @Unknown", null) shouldBe
            "Привет <@1073279640912789595> и @Unknown"
        codec.messageData("<@1073279640912789595>").allowedMentions shouldBe emptySet()
        codec.messageData(
            "<@1073279640912789595>",
            setOf("1073279640912789595"),
        ).mentionedUsers shouldBe setOf("1073279640912789595")
    }

    "strips Minecraft formatting while preserving web links" {
        DiscordMessageCodec.sanitizeMinecraftFormatting(
            "<red>Сайт</red>: https://rus-crafting.ru §aготов",
        ) shouldBe "Сайт: https://rus-crafting.ru готов"
    }

    "builds clickable direct and masked links" {
        val component = DiscordMessageCodec().minecraftBody("[сайт](https://rus-crafting.ru) и https://example.com.")
        val children = (component as TextComponent).children()

        children.filter { it.clickEvent() != null } shouldHaveSize 2
        children.filter { it.clickEvent() != null }.map { it.clickEvent()?.value() } shouldBe
            listOf("https://rus-crafting.ru", "https://example.com")
    }

    "splits outbound messages at Discord's hard limit" {
        val parts = DiscordChatService.splitMessage("a".repeat(2_001))

        parts.map(String::length) shouldBe listOf(2_000, 1)
    }

    "does not split an emoji surrogate pair at the Discord limit" {
        val parts = DiscordChatService.splitMessage("a".repeat(1_999) + "😀")

        parts.map(String::length) shouldBe listOf(1_999, 2)
        parts.joinToString("") shouldBe "a".repeat(1_999) + "😀"
    }
})
