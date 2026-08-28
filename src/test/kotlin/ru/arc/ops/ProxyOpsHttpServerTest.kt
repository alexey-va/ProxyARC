package ru.arc.ops

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class ProxyOpsHttpServerTest : FreeSpec({
    "runtime health is authenticated and exposes the agent-readable contract" {
        val fixture = discordServer("")

        val response = fixture.request("GET", "/ops/health")
        val health = ObjectMapper().readTree(response.body())

        response.statusCode() shouldBe 200
        health["component"].asText() shouldBe "proxyarc"
        health.has("ready") shouldBe true
        health.has("recoveryBacklog") shouldBe true
        health.has("activeLeases") shouldBe true
        health["schemas"].has("runtime.module_runtime") shouldBe true
        health["modules"].isArray shouldBe true
        fixture.close()
    }

    "Discord ops are deny-by-default" {
        val directory = Files.createTempDirectory("proxyarc-ops-config-")
        Files.writeString(directory.resolve("ops-http.yml"), "enabled: true")

        val config = ProxyOpsHttpConfig(ConfigManager.of(directory, "ops-http.yml"))

        config.discordReadEnabled shouldBe false
        config.discordWriteEnabled shouldBe false
        config.discordAdminEnabled shouldBe false
        config.discordAllowedGuildIds shouldBe emptySet()
        config.discordAllowedChannelIds shouldBe emptySet()
        config.discordWriteChannelIds shouldBe emptySet()
        config.discordMaxHistory shouldBe 50
        config.telegramReadEnabled shouldBe false
        config.telegramWriteEnabled shouldBe false
        config.telegramAdminEnabled shouldBe false
        config.telegramAllowedChatIds shouldBe emptySet()
        config.telegramWriteChatIds shouldBe emptySet()
        config.telegramAdminChatIds shouldBe emptySet()
    }

    "Discord ops parse explicit gates and bounded channel allowlist" {
        val directory = Files.createTempDirectory("proxyarc-ops-config-")
        Files.writeString(
            directory.resolve("ops-http.yml"),
            """
            enabled: true
            discord-read-enabled: true
            discord-write-enabled: true
            discord-admin-enabled: true
            discord-allowed-guild-ids:
              - "*"
            discord-allowed-channel-ids:
              - "1073279998359765042"
              - "1073279640912789597"
              - "1073279998359765042"
            discord-write-channel-ids:
              - "1073279640912789597"
            discord-max-history: 500
            telegram-read-enabled: true
            telegram-write-enabled: true
            telegram-admin-enabled: true
            telegram-allowed-chat-ids:
              - "-100100"
              - "-100100"
            telegram-write-chat-ids:
              - "-100100"
            telegram-admin-chat-ids:
              - "-100200"
            """.trimIndent(),
        )

        val config = ProxyOpsHttpConfig(ConfigManager.of(directory, "ops-http.yml"))

        config.discordReadEnabled shouldBe true
        config.discordWriteEnabled shouldBe true
        config.discordAdminEnabled shouldBe true
        config.discordAllowedGuildIds.shouldContainExactly("*")
        config.discordAllowedChannelIds.shouldContainExactly(
            "1073279998359765042",
            "1073279640912789597",
        )
        config.discordWriteChannelIds.shouldContainExactly("1073279640912789597")
        config.discordMaxHistory shouldBe 100
        config.telegramReadEnabled shouldBe true
        config.telegramWriteEnabled shouldBe true
        config.telegramAdminEnabled shouldBe true
        config.telegramAllowedChatIds.shouldContainExactly("-100100")
        config.telegramWriteChatIds.shouldContainExactly("-100100")
        config.telegramAdminChatIds.shouldContainExactly("-100200")
    }

    "Discord history route is bounded by config and delegates to the bot" {
        val fixture =
            discordServer(
                """
                discord-read-enabled: true
                discord-allowed-channel-ids:
                  - "1073279998359765042"
                discord-max-history: 12
                """,
            )
        val response =
            fixture.request(
                "GET",
                "/ops/discord/messages?channelId=1073279998359765042&limit=99&before=200000000000000000",
            )

        response.statusCode() shouldBe 200
        fixture.gateway.lastHistoryRequest shouldBe
            DiscordHistoryRequest(
                channelId = "1073279998359765042",
                limit = 12,
                beforeMessageId = "200000000000000000",
            )
        ObjectMapper().readTree(response.body())["messages"][0]["id"].asText() shouldBe "100"
        fixture.close()
    }

    "Discord read rejects channels outside the allowlist" {
        val fixture =
            discordServer(
                """
                discord-read-enabled: true
                discord-allowed-channel-ids:
                  - "1073279998359765042"
                discord-write-channel-ids:
                  - "1073279998359765042"
                """,
            )

        val response =
            fixture.request(
                "GET",
                "/ops/discord/messages?channelId=999999999999999999",
            )

        response.statusCode() shouldBe 403
        fixture.gateway.lastHistoryRequest shouldBe null
        fixture.close()
    }

    "Discord routes reject requests while JDA is not ready" {
        val fixture =
            discordServer(
                """
                discord-read-enabled: true
                discord-allowed-channel-ids:
                  - "1073279998359765042"
                """,
            )
        fixture.gateway.ready = false

        val response = fixture.request("GET", "/ops/discord/channels")

        response.statusCode() shouldBe 503
        fixture.close()
    }

    "Discord send requires the write gate and exact confirmation" {
        val fixture =
            discordServer(
                """
                discord-read-enabled: true
                discord-write-enabled: true
                discord-allowed-channel-ids:
                  - "1073279998359765042"
                discord-write-channel-ids:
                  - "1073279998359765042"
                """,
            )

        val rejected =
            fixture.request(
                "POST",
                "/ops/discord/messages",
                """
                {
                  "channelId": "1073279998359765042",
                  "content": "Проверка",
                  "confirmation": "yes"
                }
                """.trimIndent(),
            )
        rejected.statusCode() shouldBe 400
        fixture.gateway.lastMessageRequest shouldBe null

        val accepted =
            fixture.request(
                "POST",
                "/ops/discord/messages",
                """
                {
                  "channelId": "1073279998359765042",
                  "content": "Проверка",
                  "replyToMessageId": "100000000000000000",
                  "confirmation": "SEND 1073279998359765042"
                }
                """.trimIndent(),
            )
        accepted.statusCode() shouldBe 200
        fixture.gateway.lastMessageRequest shouldBe
            DiscordMessageMutationRequest(
                operation = DiscordMessageMutation.SEND,
                channelId = "1073279998359765042",
                content = "Проверка",
                replyToMessageId = "100000000000000000",
            )
        fixture.close()
    }

    "Discord channel admin requires its own gate, guild allowlist, and exact confirmation" {
        val fixture =
            discordServer(
                """
                discord-admin-enabled: true
                discord-allowed-guild-ids:
                  - "*"
                """,
            )
        val body =
            """
            {
              "operation": "create",
              "guildId": "100000000000000001",
              "type": "text",
              "name": "новый-канал",
              "confirmation": "DISCORD CHANNEL CREATE 100000000000000001"
            }
            """.trimIndent()

        val accepted = fixture.request("POST", "/ops/discord/channels/actions", body)

        accepted.statusCode() shouldBe 200
        fixture.gateway.lastChannelRequest shouldBe
            DiscordChannelMutationRequest(
                operation = DiscordChannelMutation.CREATE,
                guildId = "100000000000000001",
                type = "text",
                name = "новый-канал",
            )
        fixture.close()
    }

    "Discord admin rejects a mutation without the admin gate" {
        val fixture =
            discordServer(
                """
                discord-allowed-guild-ids:
                  - "*"
                """,
            )

        val response =
            fixture.request(
                "POST",
                "/ops/discord/channels/actions",
                """
                {
                  "operation": "delete",
                  "guildId": "100000000000000001",
                  "channelId": "200000000000000002",
                  "confirmation": "DISCORD CHANNEL DELETE 200000000000000002"
                }
                """.trimIndent(),
            )

        response.statusCode() shouldBe 403
        fixture.gateway.lastChannelRequest shouldBe null
        fixture.close()
    }

    "Discord search cannot bypass a bounded channel allowlist" {
        val fixture =
            discordServer(
                """
                discord-read-enabled: true
                discord-allowed-guild-ids:
                  - "*"
                discord-allowed-channel-ids:
                  - "200000000000000002"
                """,
            )

        val response =
            fixture.request(
                "GET",
                "/ops/discord/search?guildId=100000000000000001&query=test",
            )

        response.statusCode() shouldBe 400
        fixture.close()
    }

    "Discord thread update validates the target thread as well as its supplied parent" {
        val fixture =
            discordServer(
                """
                discord-write-enabled: true
                discord-write-channel-ids:
                  - "200000000000000002"
                """,
            )

        val response =
            fixture.request(
                "POST",
                "/ops/discord/threads/actions",
                """
                {
                  "operation": "update",
                  "channelId": "200000000000000002",
                  "threadId": "300000000000000003",
                  "archived": true,
                  "confirmation": "DISCORD THREAD UPDATE 300000000000000003"
                }
                """.trimIndent(),
            )

        response.statusCode() shouldBe 403
        fixture.close()
    }

    "Telegram reads are bounded by an explicit chat allowlist" {
        val fixture =
            discordServer(
                """
                telegram-read-enabled: true
                telegram-allowed-chat-ids:
                  - "-100100"
                  - "-100200"
                """,
            )

        val listed = fixture.request("GET", "/ops/telegram/chats")
        val rejected = fixture.request("GET", "/ops/telegram/chat?chatId=-100300")

        listed.statusCode() shouldBe 200
        fixture.telegramGateway.lastListedChatIds.shouldContainExactly("-100100", "-100200")
        rejected.statusCode() shouldBe 403
        fixture.telegramGateway.lastReadChatId shouldBe null
        fixture.close()
    }

    "Telegram chat listing rejects mutable or malformed configured ids" {
        val fixture =
            discordServer(
                """
                telegram-read-enabled: true
                telegram-allowed-chat-ids:
                  - "@mutable-name"
                """,
            )

        val response = fixture.request("GET", "/ops/telegram/chats")

        response.statusCode() shouldBe 400
        fixture.telegramGateway.lastListedChatIds shouldBe emptySet()
        fixture.close()
    }

    "Telegram message actions require write scope and exact confirmation" {
        val fixture =
            discordServer(
                """
                telegram-write-enabled: true
                telegram-write-chat-ids:
                  - "-100100"
                """,
            )
        val body =
            """
            {
              "operation": "send",
              "chatId": "-100100",
              "threadId": 7,
              "text": "Новости сервера",
              "disableNotification": true,
              "confirmation": "TELEGRAM MESSAGE SEND -100100"
            }
            """.trimIndent()

        val rejected =
            fixture.request(
                "POST",
                "/ops/telegram/messages/actions",
                body.replace("TELEGRAM MESSAGE SEND -100100", "yes"),
            )
        rejected.statusCode() shouldBe 400
        fixture.telegramGateway.lastMessageRequest shouldBe null

        val accepted = fixture.request("POST", "/ops/telegram/messages/actions", body)

        accepted.statusCode() shouldBe 200
        fixture.telegramGateway.lastMessageRequest shouldBe
            TelegramMessageMutationRequest(
                operation = TelegramMessageMutation.SEND,
                chatId = "-100100",
                threadId = 7,
                text = "Новости сервера",
                disableNotification = true,
            )
        fixture.close()
    }

    "Telegram routes reject requests while the bot is not ready" {
        val fixture =
            discordServer(
                """
                telegram-read-enabled: true
                telegram-allowed-chat-ids:
                  - "-100100"
                """,
            )
        fixture.telegramGateway.ready = false

        val response = fixture.request("GET", "/ops/telegram/chat?chatId=-100100")

        response.statusCode() shouldBe 503
        fixture.telegramGateway.lastReadChatId shouldBe null
        fixture.close()
    }

    "Telegram channel title and description require admin scope" {
        val fixture =
            discordServer(
                """
                telegram-admin-enabled: true
                telegram-admin-chat-ids:
                  - "-100200"
                """,
            )
        val body =
            """
            {
              "operation": "update",
              "chatId": "-100200",
              "title": "RusCrafting — новости",
              "description": "Новости, события и обновления сервера",
              "confirmation": "TELEGRAM CHAT UPDATE -100200"
            }
            """.trimIndent()

        val accepted = fixture.request("POST", "/ops/telegram/chats/actions", body)

        accepted.statusCode() shouldBe 200
        fixture.telegramGateway.lastChatRequest shouldBe
            TelegramChatMutationRequest(
                operation = TelegramChatMutation.UPDATE,
                chatId = "-100200",
                title = "RusCrafting — новости",
                description = "Новости, события и обновления сервера",
            )
        fixture.close()
    }

    "Telegram mutations reject malformed ids before calling the bot" {
        val fixture =
            discordServer(
                """
                telegram-write-enabled: true
                telegram-write-chat-ids:
                  - "*"
                """,
            )

        val response =
            fixture.request(
                "POST",
                "/ops/telegram/messages/actions",
                """
                {
                  "operation": "delete",
                  "chatId": "@mutable-name",
                  "messageId": 5,
                  "confirmation": "TELEGRAM MESSAGE DELETE 5"
                }
                """.trimIndent(),
            )

        response.statusCode() shouldBe 400
        fixture.telegramGateway.lastMessageRequest shouldBe null
        fixture.close()
    }

    "Telegram rich posts support formatting buttons and bounded attachments" {
        val fixture =
            discordServer(
                """
                telegram-write-enabled: true
                telegram-write-chat-ids:
                  - "-100100"
                """,
            )

        val response =
            fixture.request(
                "POST",
                "/ops/telegram/messages/actions",
                """
                {
                  "operation": "send",
                  "chatId": "-100100",
                  "text": "<b>Обновление</b>",
                  "parseMode": "html",
                  "protectContent": true,
                  "buttons": [[{"text": "Открыть", "url": "https://ruscrafting.ru/news"}]],
                  "attachment": {
                    "type": "photo",
                    "fileName": "news.png",
                    "dataBase64": "aW1hZ2U=",
                    "hasSpoiler": false
                  },
                  "confirmation": "TELEGRAM MESSAGE SEND -100100"
                }
                """.trimIndent(),
            )

        response.statusCode() shouldBe 200
        fixture.telegramGateway.lastMessageRequest shouldBe
            TelegramMessageMutationRequest(
                operation = TelegramMessageMutation.SEND,
                chatId = "-100100",
                text = "<b>Обновление</b>",
                parseMode = TelegramParseMode.HTML,
                protectContent = true,
                buttons = listOf(listOf(TelegramButtonSpec("Открыть", "https://ruscrafting.ru/news"))),
                attachment =
                    TelegramAttachmentSpec(
                        type = TelegramAttachmentType.PHOTO,
                        fileName = "news.png",
                        dataBase64 = "aW1hZ2U=",
                        hasSpoiler = false,
                    ),
            )
        fixture.close()
    }

    "Telegram forum topics and permissions use the admin scope" {
        val fixture =
            discordServer(
                """
                telegram-admin-enabled: true
                telegram-admin-chat-ids:
                  - "-100200"
                """,
            )

        val topic =
            fixture.request(
                "POST",
                "/ops/telegram/topics/actions",
                """
                {
                  "operation": "create",
                  "chatId": "-100200",
                  "name": "Обновления",
                  "iconColor": 7322096,
                  "confirmation": "TELEGRAM TOPIC CREATE -100200"
                }
                """.trimIndent(),
            )
        topic.statusCode() shouldBe 200
        fixture.telegramGateway.lastTopicRequest shouldBe
            TelegramTopicMutationRequest(
                operation = TelegramTopicMutation.CREATE,
                chatId = "-100200",
                name = "Обновления",
                iconColor = 7_322_096,
            )

        val permissions =
            fixture.request(
                "POST",
                "/ops/telegram/chats/actions",
                """
                {
                  "operation": "set_permissions",
                  "chatId": "-100200",
                  "permissions": {"canSendMessages": true, "canManageTopics": false},
                  "useIndependentPermissions": true,
                  "confirmation": "TELEGRAM CHAT SET_PERMISSIONS -100200"
                }
                """.trimIndent(),
            )
        permissions.statusCode() shouldBe 200
        fixture.telegramGateway.lastChatRequest shouldBe
            TelegramChatMutationRequest(
                operation = TelegramChatMutation.SET_PERMISSIONS,
                chatId = "-100200",
                permissions = TelegramChatPermissionsSpec(canSendMessages = true, canManageTopics = false),
                useIndependentPermissions = true,
            )
        fixture.close()
    }

    "Telegram invite creation is separately confirmed" {
        val fixture =
            discordServer(
                """
                telegram-admin-enabled: true
                telegram-admin-chat-ids:
                  - "-100200"
                """,
            )

        val response =
            fixture.request(
                "POST",
                "/ops/telegram/invites/actions",
                """
                {
                  "operation": "create",
                  "chatId": "-100200",
                  "name": "Discord bridge",
                  "memberLimit": 100,
                  "confirmation": "TELEGRAM INVITE CREATE -100200"
                }
                """.trimIndent(),
            )

        response.statusCode() shouldBe 200
        fixture.telegramGateway.lastInviteRequest shouldBe
            TelegramInviteMutationRequest(
                operation = TelegramInviteMutation.CREATE,
                chatId = "-100200",
                name = "Discord bridge",
                memberLimit = 100,
            )
        fixture.close()
    }

    "stop shuts down the worker executor" {
        val directory = Files.createTempDirectory("proxyarc-ops-server-")
        Files.writeString(
            directory.resolve("ops-http.yml"),
            """
            enabled: true
            token: unit-test-token
            bind-host: 127.0.0.1
            bind-port: 0
            """.trimIndent(),
        )
        val config = ProxyOpsHttpConfig(ConfigManager.of(directory, "ops-http.yml"))
        val executor = Executors.newSingleThreadExecutor()
        val server = ProxyOpsHttpServer({ executor }, { config })
        server.start()

        server.stop()

        executor.isShutdown shouldBe true
    }
})

private class DiscordServerFixture(
    val server: ProxyOpsHttpServer,
    val gateway: FakeDiscordOpsGateway,
    val telegramGateway: FakeTelegramOpsGateway,
) : AutoCloseable {
    private val client = HttpClient.newHttpClient()

    fun request(
        method: String,
        path: String,
        body: String? = null,
    ): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder(URI("http://127.0.0.1:${server.actualPort}$path"))
                .header("Authorization", "Bearer unit-test-token")
                .header("Content-Type", "application/json")
        val publisher =
            if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofString(body)
            }
        return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    override fun close() {
        server.stop()
    }
}

private fun discordServer(extraConfig: String): DiscordServerFixture {
    val directory = Files.createTempDirectory("proxyarc-discord-ops-")
    val configText =
        """
        enabled: true
        token: unit-test-token
        bind-host: 127.0.0.1
        bind-port: 0
        """.trimIndent() + "\n" + extraConfig.trimIndent()
    Files.writeString(
        directory.resolve("ops-http.yml"),
        configText,
    )
    val config = ProxyOpsHttpConfig(ConfigManager.of(directory, "ops-http.yml"))
    val gateway = FakeDiscordOpsGateway()
    val telegramGateway = FakeTelegramOpsGateway()
    val server =
        ProxyOpsHttpServer(
            executorFactory = { Executors.newSingleThreadExecutor() },
            configProvider = { config },
            discordProvider = { gateway },
            telegramProvider = { telegramGateway },
        )
    server.start()
    return DiscordServerFixture(server, gateway, telegramGateway)
}

private class FakeDiscordOpsGateway : DiscordOpsGateway {
    var ready = true
    var lastHistoryRequest: DiscordHistoryRequest? = null
    var lastMessageRequest: DiscordMessageMutationRequest? = null
    var lastChannelRequest: DiscordChannelMutationRequest? = null

    override fun isReady(): Boolean = ready

    override fun isGuildAllowed(
        guildId: String,
        allowedGuildIds: Set<String>,
    ): Boolean = "*" in allowedGuildIds || guildId in allowedGuildIds

    override fun isChannelAllowed(
        channelId: String,
        allowedGuildIds: Set<String>,
        allowedChannelIds: Set<String>,
    ): Boolean = "*" in allowedChannelIds || channelId in allowedChannelIds

    override fun listGuilds(allowedGuildIds: Set<String>): Map<String, Any?> =
        mapOf("guilds" to emptyList<Any>())

    override fun listChannels(
        allowedGuildIds: Set<String>,
        allowedChannelIds: Set<String>,
    ): Map<String, Any?> =
        mapOf("channels" to emptyList<Any>())

    override fun listRoles(guildId: String): Map<String, Any?> = mapOf("guildId" to guildId, "roles" to emptyList<Any>())

    override fun readMember(request: DiscordMemberReadRequest): CompletableFuture<Map<String, Any?>> =
        CompletableFuture.completedFuture(mapOf("userId" to request.userId))

    override fun readHistory(request: DiscordHistoryRequest): CompletableFuture<Map<String, Any?>> {
        lastHistoryRequest = request
        return CompletableFuture.completedFuture(
            mapOf(
                "channelId" to request.channelId,
                "messages" to listOf(mapOf("id" to "100")),
            ),
        )
    }

    override fun readMessage(request: DiscordMessageRequest): CompletableFuture<Map<String, Any?>> =
        CompletableFuture.completedFuture(mapOf("id" to request.messageId))

    override fun readPins(request: DiscordPinsRequest): CompletableFuture<Map<String, Any?>> =
        CompletableFuture.completedFuture(mapOf("pins" to emptyList<Any>()))

    override fun searchMessages(request: DiscordSearchRequest): CompletableFuture<Map<String, Any?>> =
        CompletableFuture.completedFuture(mapOf("messages" to emptyList<Any>()))

    override fun mutateMessage(request: DiscordMessageMutationRequest): CompletableFuture<Map<String, Any?>> {
        lastMessageRequest = request
        return CompletableFuture.completedFuture(mapOf("id" to "101"))
    }

    override fun mutateThread(request: DiscordThreadMutationRequest): CompletableFuture<Map<String, Any?>> =
        CompletableFuture.completedFuture(mapOf("threadId" to (request.threadId ?: "102")))

    override fun mutateChannel(request: DiscordChannelMutationRequest): CompletableFuture<Map<String, Any?>> {
        lastChannelRequest = request
        return CompletableFuture.completedFuture(mapOf("channelId" to (request.channelId ?: "103")))
    }

    override fun mutateRole(request: DiscordRoleMutationRequest): CompletableFuture<Map<String, Any?>> =
        CompletableFuture.completedFuture(mapOf("roleId" to (request.roleId ?: "104")))

    override fun mutateMember(request: DiscordMemberMutationRequest): CompletableFuture<Map<String, Any?>> =
        CompletableFuture.completedFuture(mapOf("userId" to request.userId))
}

private class FakeTelegramOpsGateway : TelegramOpsGateway {
    var ready = true
    var lastListedChatIds: Set<String> = emptySet()
    var lastReadChatId: String? = null
    var lastMessageRequest: TelegramMessageMutationRequest? = null
    var lastChatRequest: TelegramChatMutationRequest? = null
    var lastTopicRequest: TelegramTopicMutationRequest? = null
    var lastInviteRequest: TelegramInviteMutationRequest? = null

    override fun isReady(): Boolean = ready

    override fun listChats(chatIds: Set<String>): CompletableFuture<Map<String, Any?>> {
        lastListedChatIds = chatIds
        return CompletableFuture.completedFuture(
            mapOf("chats" to chatIds.map { mapOf("id" to it, "type" to "channel") }),
        )
    }

    override fun readChat(chatId: String): CompletableFuture<Map<String, Any?>> {
        lastReadChatId = chatId
        return CompletableFuture.completedFuture(mapOf("id" to chatId, "type" to "channel"))
    }

    override fun mutateMessage(request: TelegramMessageMutationRequest): CompletableFuture<Map<String, Any?>> {
        lastMessageRequest = request
        return CompletableFuture.completedFuture(mapOf("chatId" to request.chatId, "messageId" to 42))
    }

    override fun mutateChat(request: TelegramChatMutationRequest): CompletableFuture<Map<String, Any?>> {
        lastChatRequest = request
        return CompletableFuture.completedFuture(
            mapOf("chatId" to request.chatId, "updated" to listOf("title", "description")),
        )
    }

    override fun mutateTopic(request: TelegramTopicMutationRequest): CompletableFuture<Map<String, Any?>> {
        lastTopicRequest = request
        return CompletableFuture.completedFuture(
            mapOf("chatId" to request.chatId, "threadId" to (request.threadId ?: 77)),
        )
    }

    override fun mutateInvite(request: TelegramInviteMutationRequest): CompletableFuture<Map<String, Any?>> {
        lastInviteRequest = request
        return CompletableFuture.completedFuture(mapOf("chatId" to request.chatId, "inviteLink" to "redacted"))
    }
}
