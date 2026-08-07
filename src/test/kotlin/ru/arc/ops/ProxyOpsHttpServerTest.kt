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
    "Discord ops are deny-by-default" {
        val directory = Files.createTempDirectory("proxyarc-ops-config-")
        Files.writeString(directory.resolve("ops-http.yml"), "enabled: true")

        val config = ProxyOpsHttpConfig(ConfigManager.of(directory, "ops-http.yml"))

        config.discordReadEnabled shouldBe false
        config.discordWriteEnabled shouldBe false
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
        fixture.gateway.lastSendRequest shouldBe null

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
        fixture.gateway.lastSendRequest shouldBe
            DiscordSendRequest(
                channelId = "1073279998359765042",
                content = "Проверка",
                replyToMessageId = "100000000000000000",
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
    var lastSendRequest: DiscordSendRequest? = null

    override fun isReady(): Boolean = ready

    override fun isChannelAllowed(
        channelId: String,
        allowedChannelIds: Set<String>,
    ): Boolean = channelId in allowedChannelIds

    override fun listChannels(allowedChannelIds: Set<String>): Map<String, Any?> =
        mapOf("channels" to emptyList<Any>())

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

    override fun sendMessage(request: DiscordSendRequest): CompletableFuture<Map<String, Any?>> {
        lastSendRequest = request
        return CompletableFuture.completedFuture(mapOf("id" to "101"))
    }
}
