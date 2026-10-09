package ru.arc.ops

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.chat.ProxyGlobalChatDelivery
import ru.arc.chat.ProxyGlobalChatDeliveryResult
import ru.arc.chat.ProxyGlobalChatService
import ru.arc.chat.ProxyGlobalChatSinkReceipt
import ru.arc.config.ConfigManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ProxyGlobalChatHttpTest : FreeSpec({
    "authenticated chat read and send use a fixed identity and idempotent receipts" {
        val fixture = GlobalChatHttpFixture()
        val initial = fixture.request("GET", "/ops/chat?limit=10")
        val initialJson = ObjectMapper().readTree(initial.body())
        val instanceId = initialJson["instanceId"].asText()

        initial.statusCode() shouldBe 200
        initialJson["historyGap"].asBoolean() shouldBe true
        initialJson["gapReason"].asText() shouldBe "instance-start"

        val body =
            """
            {
              "requestId": "$REQUEST_ID",
              "instanceId": "$instanceId",
              "content": "Could you share the error text?"
            }
            """.trimIndent()
        val sent = fixture.request("POST", "/ops/chat", body)
        val replay = fixture.request("POST", "/ops/chat", body)

        sent.statusCode() shouldBe 200
        ObjectMapper().readTree(sent.body())["replayed"].asBoolean() shouldBe false
        ObjectMapper().readTree(sent.body())["deliveries"]["telegram"]["messageId"].asInt() shouldBe 91
        replay.statusCode() shouldBe 200
        ObjectMapper().readTree(replay.body())["replayed"].asBoolean() shouldBe true
        fixture.delivery.sendCount.get() shouldBe 1

        val recent = ObjectMapper().readTree(fixture.request("GET", "/ops/chat?limit=10").body())
        val messages = recent["messages"]
        val codexMessage = messages[messages.size() - 1]
        codexMessage["source"].asText() shouldBe "codex"
        codexMessage["author"].asText() shouldBe "Codex"
        codexMessage["content"].asText() shouldBe "Could you share the error text?"
        fixture.close()
    }

    "chat rejects a stale instance, malformed query, and unauthenticated caller" {
        val fixture = GlobalChatHttpFixture()
        val stale =
            fixture.request(
                "POST",
                "/ops/chat",
                """{"requestId":"$REQUEST_ID","instanceId":"$OTHER_INSTANCE","content":"hello"}""",
            )
        val badLimit = fixture.request("GET", "/ops/chat?limit=1001")
        val blankCursor = fixture.request("GET", "/ops/chat?after=")
        val unauthenticated = fixture.request("GET", "/ops/chat", authorize = false)

        stale.statusCode() shouldBe 409
        ObjectMapper().readTree(stale.body())["ok"].asBoolean() shouldBe false
        badLimit.statusCode() shouldBe 400
        blankCursor.statusCode() shouldBe 400
        unauthenticated.statusCode() shouldBe 401
        fixture.delivery.sendCount.get() shouldBe 0
        fixture.close()
    }
})

private class GlobalChatHttpFixture : AutoCloseable {
    private val client = HttpClient.newHttpClient()
    private val service = ProxyGlobalChatService()
    val delivery = FakeGlobalChatDelivery()
    private val directory = Files.createTempDirectory("proxyarc-global-chat-http-")
    private val config = run {
        Files.writeString(
            directory.resolve("ops-http.yml"),
            """
            enabled: true
            token: unit-test-token
            bind-host: 127.0.0.1
            bind-port: 0
            """.trimIndent(),
        )
        ProxyOpsHttpConfig(ConfigManager.of(directory, "ops-http.yml"))
    }
    private val server =
        ProxyOpsHttpServer(
            executorFactory = { Executors.newSingleThreadExecutor() },
            configProvider = { config },
            discordProvider = { null },
            telegramProvider = { null },
            globalChatProvider = { service },
            globalChatDeliveryProvider = { delivery },
        ).also { it.start() }

    fun request(
        method: String,
        path: String,
        body: String? = null,
        authorize: Boolean = true,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.actualPort}$path"))
        if (authorize) builder.header("Authorization", "Bearer unit-test-token")
        builder.header("Content-Type", "application/json")
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    override fun close() = server.stop()
}

private class FakeGlobalChatDelivery : ProxyGlobalChatDelivery {
    val sendCount = AtomicInteger()

    override fun send(content: String): CompletableFuture<ProxyGlobalChatDeliveryResult> {
        sendCount.incrementAndGet()
        return CompletableFuture.completedFuture(
            ProxyGlobalChatDeliveryResult(
                minecraft = ProxyGlobalChatSinkReceipt("accepted", recipients = 2),
                discord = ProxyGlobalChatSinkReceipt("accepted"),
                telegram = ProxyGlobalChatSinkReceipt("confirmed", messageId = 91, threadId = 2, chatId = "-1001"),
            ),
        )
    }
}

private const val REQUEST_ID = "00000000-0000-0000-0000-0000000000a1"
private const val OTHER_INSTANCE = "00000000-0000-0000-0000-0000000000b2"
