package ru.arc.channelsync

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ops.DiscordChannelMutationRequest
import ru.arc.ops.DiscordHistoryRequest
import ru.arc.ops.DiscordMemberMutationRequest
import ru.arc.ops.DiscordMemberReadRequest
import ru.arc.ops.DiscordMessageMutation
import ru.arc.ops.DiscordMessageMutationRequest
import ru.arc.ops.DiscordMessageRequest
import ru.arc.ops.DiscordOpsGateway
import ru.arc.ops.DiscordPinsRequest
import ru.arc.ops.DiscordRoleMutationRequest
import ru.arc.ops.DiscordSearchRequest
import ru.arc.ops.DiscordThreadMutationRequest
import ru.arc.ops.TelegramChatMutationRequest
import ru.arc.ops.TelegramInviteMutationRequest
import ru.arc.ops.TelegramMessageMutation
import ru.arc.ops.TelegramMessageMutationRequest
import ru.arc.ops.TelegramOpsGateway
import ru.arc.ops.TelegramParseMode
import ru.arc.ops.TelegramTopicMutationRequest
import ru.arc.telegram.TelegramDestination
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class ChannelSyncServiceTest : FreeSpec({
    val mapping =
        ChannelSyncMapping(
            id = "community",
            discordChannelId = "1073279998359765042",
            telegram = TelegramDestination("-1001234567890", 42),
        )

    "relays replies edits and deletes without echo duplicates" {
        val root = Files.createTempDirectory("channel-sync-")
        val discord = TrackingDiscordGateway()
        val telegram = TrackingTelegramGateway()
        val service =
            ChannelSyncService(
                mappings = listOf(mapping),
                links = ChannelSyncLinkStore(root),
                discordProvider = { discord },
                telegramProvider = { telegram },
            )

        service.relayDiscord(
            DiscordSyncMessage(mapping.discordChannelId, "100000000000000001", "Alex", "Привет"),
        ) shouldBe true
        telegram.messages.single().text shouldBe "Alex » Привет"

        service.relayDiscord(
            DiscordSyncMessage(mapping.discordChannelId, "100000000000000001", "Alex", "Привет"),
        ) shouldBe true
        telegram.messages.size shouldBe 1

        service.relayDiscord(
            DiscordSyncMessage(
                mapping.discordChannelId,
                "100000000000000002",
                "Nika",
                "Ответ",
                replyToMessageId = "100000000000000001",
            ),
        ) shouldBe true
        telegram.messages.last().replyToMessageId shouldBe 42

        service.editDiscord(
            DiscordSyncMessage(mapping.discordChannelId, "100000000000000001", "Alex", "Исправлено"),
        ) shouldBe true
        telegram.messages.last().operation shouldBe TelegramMessageMutation.EDIT
        telegram.messages.last().messageId shouldBe 42

        service.deleteDiscord(mapping.discordChannelId, "100000000000000001") shouldBe true
        telegram.messages.last().operation shouldBe TelegramMessageMutation.DELETE
        telegram.messages.last().messageId shouldBe 42

        service.editTelegram(
            TelegramSyncMessage(mapping.telegram.chatId, mapping.telegram.threadId, 42, "Telegram", "Эхо"),
        ) shouldBe true
        discord.messages.size shouldBe 0

        service.relayTelegram(
            TelegramSyncMessage(mapping.telegram.chatId, mapping.telegram.threadId, 500, "Mira", "Из Telegram"),
        ) shouldBe true
        discord.messages.single().content shouldBe "**Mira** » Из Telegram"

        service.relayTelegram(
            TelegramSyncMessage(
                mapping.telegram.chatId,
                mapping.telegram.threadId,
                501,
                "Mira",
                "Ответ",
                replyToMessageId = 500,
            ),
        ) shouldBe true
        discord.messages.last().replyToMessageId shouldBe "200000000000000001"

        service.editTelegram(
            TelegramSyncMessage(mapping.telegram.chatId, mapping.telegram.threadId, 500, "Mira", "Исправлено"),
        ) shouldBe true
        discord.messages.last().operation shouldBe DiscordMessageMutation.EDIT
        discord.messages.last().messageId shouldBe "200000000000000001"

        val telegramRequestCount = telegram.messages.size
        service.editDiscord(
            DiscordSyncMessage(mapping.discordChannelId, "200000000000000001", "Discord", "Эхо"),
        ) shouldBe true
        telegram.messages.size shouldBe telegramRequestCount
    }

    "persists completed message links across restart" {
        val root = Files.createTempDirectory("channel-sync-store-")
        val first = ChannelSyncLinkStore(root)
        first.reserveDiscord(mapping, "100000000000000010") shouldBe true
        first.completeDiscord(mapping, "100000000000000010", 91)

        val restored = ChannelSyncLinkStore(root)

        restored.byDiscord(mapping.discordChannelId, "100000000000000010")?.telegramMessageId shouldBe 91
        restored.reserveDiscord(mapping, "100000000000000010") shouldBe false
    }

    "suppresses a Telegram echo while its outbound send is still pending" {
        val root = Files.createTempDirectory("channel-sync-pending-")
        val discord = TrackingDiscordGateway()
        val telegram = TrackingTelegramGateway()
        val pending = CompletableFuture<Map<String, Any?>>()
        telegram.nextResponse = pending
        val service =
            ChannelSyncService(
                mappings = listOf(mapping),
                links = ChannelSyncLinkStore(root),
                discordProvider = { discord },
                telegramProvider = { telegram },
            )

        service.relayDiscord(
            DiscordSyncMessage(mapping.discordChannelId, "100000000000000020", "Alex", "Привет"),
        ) shouldBe true
        service.relayTelegram(
            TelegramSyncMessage(mapping.telegram.chatId, mapping.telegram.threadId, 700, "Telegram", "Alex » Привет"),
        ) shouldBe true
        discord.messages.size shouldBe 0

        pending.complete(mapOf("messageId" to 700))
        service.relayTelegram(
            TelegramSyncMessage(mapping.telegram.chatId, mapping.telegram.threadId, 700, "Telegram", "Alex » Привет"),
        ) shouldBe true
        discord.messages.size shouldBe 0
    }

    "releases an inbound event for legacy fallback while its mapped destination is offline" {
        val root = Files.createTempDirectory("channel-sync-offline-")
        val discord = TrackingDiscordGateway()
        val telegram = TrackingTelegramGateway().also { it.ready = false }
        val service =
            ChannelSyncService(
                mappings = listOf(mapping),
                links = ChannelSyncLinkStore(root),
                discordProvider = { discord },
                telegramProvider = { telegram },
            )
        val message = DiscordSyncMessage(mapping.discordChannelId, "100000000000000030", "Alex", "Привет")

        service.relayDiscord(message) shouldBe false
        telegram.ready = true
        service.relayDiscord(message) shouldBe true
        telegram.messages.size shouldBe 1
    }

    "renders placeholders in one pass" {
        ChannelSyncService.render("%sender% » %message%", "%message%", "%sender%") shouldBe "%message% » %sender%"
    }

    "applies cross-platform mention translation at the relay boundary" {
        val root = Files.createTempDirectory("channel-sync-mentions-")
        val discord = TrackingDiscordGateway()
        val telegram = TrackingTelegramGateway()
        val discordUserId = "1073279640912789595"
        val service =
            ChannelSyncService(
                mappings = listOf(mapping),
                links = ChannelSyncLinkStore(root),
                discordProvider = { discord },
                telegramProvider = { telegram },
                identityResolver =
                    ChannelSyncIdentityResolver(
                        telegramByDiscordUserId = { TelegramMentionTarget(777L, "PlayerOne") },
                        discordByTelegramUserId = { DiscordMentionTarget(discordUserId, "PlayerOne") },
                    ),
            )

        service.relayDiscord(
            DiscordSyncMessage(
                mapping.discordChannelId,
                "100000000000000040",
                "Alex",
                "Привет <@$discordUserId>",
                technical = DiscordSyncTechnicalText(userNamesById = mapOf(discordUserId to "Discord Name")),
            ),
        ) shouldBe true
        telegram.messages.single().text shouldBe
            "Alex » Привет <a href=\"tg://user?id=777\">@PlayerOne</a>"
        telegram.messages.single().parseMode shouldBe TelegramParseMode.HTML

        service.relayTelegram(
            TelegramSyncMessage(
                mapping.telegram.chatId,
                mapping.telegram.threadId,
                900,
                "Mira",
                "PlayerOne",
                entities = listOf(TelegramSyncEntity("text_mention", 0, 9, userId = 777L)),
            ),
        ) shouldBe true
        discord.messages.single().content shouldBe "**Mira** » <@$discordUserId>"
        discord.messages.single().allowedUserMentionIds shouldBe setOf(discordUserId)
    }
})

private class TrackingTelegramGateway : TelegramOpsGateway {
    val messages = mutableListOf<TelegramMessageMutationRequest>()
    var ready = true
    var nextResponse: CompletableFuture<Map<String, Any?>>? = null
    private var nextMessageId = 42

    override fun isReady(): Boolean = ready

    override fun listChats(chatIds: Set<String>) = completed(mapOf<String, Any?>())

    override fun readChat(chatId: String) = completed(mapOf<String, Any?>())

    override fun mutateMessage(request: TelegramMessageMutationRequest): CompletableFuture<Map<String, Any?>> {
        messages += request
        return nextResponse?.also { nextResponse = null } ?: completed(mapOf("messageId" to nextMessageId++))
    }

    override fun mutateChat(request: TelegramChatMutationRequest) = completed(mapOf<String, Any?>())

    override fun mutateTopic(request: TelegramTopicMutationRequest) = completed(mapOf<String, Any?>())

    override fun mutateInvite(request: TelegramInviteMutationRequest) = completed(mapOf<String, Any?>())
}

private class TrackingDiscordGateway : DiscordOpsGateway {
    val messages = mutableListOf<DiscordMessageMutationRequest>()
    private var nextMessageId = 200_000_000_000_000_001L

    override fun isReady(): Boolean = true

    override fun isGuildAllowed(guildId: String, allowedGuildIds: Set<String>) = true

    override fun isChannelAllowed(channelId: String, allowedGuildIds: Set<String>, allowedChannelIds: Set<String>) = true

    override fun listGuilds(allowedGuildIds: Set<String>) = emptyMap<String, Any?>()

    override fun listChannels(allowedGuildIds: Set<String>, allowedChannelIds: Set<String>) = emptyMap<String, Any?>()

    override fun listRoles(guildId: String) = emptyMap<String, Any?>()

    override fun readMember(request: DiscordMemberReadRequest) = completed(mapOf<String, Any?>())

    override fun readHistory(request: DiscordHistoryRequest) = completed(mapOf<String, Any?>())

    override fun readMessage(request: DiscordMessageRequest) = completed(mapOf<String, Any?>())

    override fun readPins(request: DiscordPinsRequest) = completed(mapOf<String, Any?>())

    override fun searchMessages(request: DiscordSearchRequest) = completed(mapOf<String, Any?>())

    override fun mutateMessage(request: DiscordMessageMutationRequest): CompletableFuture<Map<String, Any?>> {
        messages += request
        return completed(mapOf("id" to (nextMessageId++).toString()))
    }

    override fun mutateThread(request: DiscordThreadMutationRequest) = completed(mapOf<String, Any?>())

    override fun mutateChannel(request: DiscordChannelMutationRequest) = completed(mapOf<String, Any?>())

    override fun mutateRole(request: DiscordRoleMutationRequest) = completed(mapOf<String, Any?>())

    override fun mutateMember(request: DiscordMemberMutationRequest) = completed(mapOf<String, Any?>())
}

private fun completed(value: Map<String, Any?>): CompletableFuture<Map<String, Any?>> =
    CompletableFuture.completedFuture(value)
