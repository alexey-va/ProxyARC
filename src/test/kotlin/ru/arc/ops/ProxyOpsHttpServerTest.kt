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
    val server =
        ProxyOpsHttpServer(
            executorFactory = { Executors.newSingleThreadExecutor() },
            configProvider = { config },
            discordProvider = { gateway },
        )
    server.start()
    return DiscordServerFixture(server, gateway)
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
