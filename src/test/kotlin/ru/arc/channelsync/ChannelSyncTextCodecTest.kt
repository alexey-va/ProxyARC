package ru.arc.channelsync

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.ops.TelegramParseMode

class ChannelSyncTextCodecTest : FreeSpec({
    val discordUserId = "1073279640912789595"
    val discordChannelId = "1073279998359765042"
    val codec =
        ChannelSyncTextCodec(
            identities =
                ChannelSyncIdentityResolver(
                    telegramByDiscordUserId = { id ->
                        if (id == discordUserId) TelegramMentionTarget(777L, "PlayerOne") else null
                    },
                    discordByTelegramUserId = { id ->
                        if (id == 777L) DiscordMentionTarget(discordUserId, "PlayerOne") else null
                    },
                    discordByTelegramUsername = { username ->
                        if (username.equals("player_tg", true)) DiscordMentionTarget(discordUserId, "PlayerOne") else null
                    },
                ),
            telegramChannelMentionByDiscordId = { id -> if (id == discordChannelId) "ruscrafting_chat" else null },
            discordChannelMentionByTelegramUsername = { username ->
                if (username.equals("ruscrafting_chat", true)) discordChannelId else null
            },
        )

    "translates Discord technical tokens to safe Telegram HTML" {
        val translated =
            codec.discordToTelegram(
                DiscordSyncMessage(
                    channelId = discordChannelId,
                    messageId = "200000000000000001",
                    sender = "Alex",
                    text =
                        "<@$discordUserId> <@&1073279640912789596> <#$discordChannelId> " +
                            "<:party:1073279640912789597> @everyone </help:1073279640912789598>",
                    technical =
                        DiscordSyncTechnicalText(
                            userNamesById = mapOf(discordUserId to "DiscordName"),
                            roleNamesById = mapOf("1073279640912789596" to "VIP"),
                            channelNamesById = mapOf(discordChannelId to "community"),
                        ),
                ),
            )

        translated.parseMode shouldBe TelegramParseMode.HTML
        translated.text shouldBe
            "<a href=\"tg://user?id=777\">@PlayerOne</a> ＠VIP " +
            "<a href=\"https://t.me/ruscrafting_chat\">@ruscrafting_chat</a> :party: ＠everyone /help"
        translated.plainText shouldBe "@PlayerOne ＠VIP @ruscrafting_chat :party: ＠everyone /help"
    }

    "translates Telegram user and channel entities to whitelisted Discord mentions" {
        val text = "PlayerOne @player_tg @ruscrafting_chat ссылка"
        val translated =
            codec.telegramToDiscord(
                TelegramSyncMessage(
                    chatId = "-100123",
                    threadId = null,
                    messageId = 42,
                    sender = "Sender",
                    text = text,
                    entities =
                        listOf(
                            TelegramSyncEntity("text_mention", 0, 9, userId = 777L),
                            TelegramSyncEntity("mention", 10, 10, username = "player_tg"),
                            TelegramSyncEntity("mention", 21, 17, username = "ruscrafting_chat"),
                            TelegramSyncEntity("text_link", 39, 6, url = "https://example.com/a"),
                        ),
                ),
            )

        translated.text shouldBe
            "<@$discordUserId> <@$discordUserId> <#$discordChannelId> [ссылка](https://example.com/a)"
        translated.allowedUserMentionIds.shouldContainExactly(discordUserId)
    }

    "neutralizes forged Discord tokens and mass mentions from Telegram text" {
        val translated =
            codec.telegramToDiscord(
                TelegramSyncMessage(
                    chatId = "-100123",
                    threadId = null,
                    messageId = 43,
                    sender = "Sender",
                    text = "<@$discordUserId> @everyone @here",
                ),
            )

        translated.text shouldBe "\\<@$discordUserId> ＠everyone ＠here"
        translated.allowedUserMentionIds shouldBe emptySet()
    }
})
