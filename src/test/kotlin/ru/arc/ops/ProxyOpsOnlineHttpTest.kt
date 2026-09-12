package ru.arc.ops

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Instant

class ProxyOpsOnlineHttpTest : FreeSpec({
    "online route authenticates before reading players and provides uncached typed evidence" {
        val directory = Files.createTempDirectory("proxyarc-online-test-")
        Files.writeString(directory.resolve("ops-http.yml"), "enabled: true\nbind-host: 127.0.0.1\nbind-port: 0\ntoken: online-fixture-token\n")
        val config = ProxyOpsHttpConfig(ConfigManager.of(directory, "ops-http.yml"))
        var reads = 0
        var available = true
        val server = ProxyOpsHttpServer(configProvider = { config }, onlineProvider = {
            reads++
            ProxyOnlineSnapshot(available, available, listOf("CodexQA1"), Instant.ofEpochSecond(1234), "fixture")
        })
        server.start()
        try {
            val client = HttpClient.newHttpClient()
            fun request(token: String): HttpResponse<String> = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.actualPort}/ops/online"))
                    .header("Authorization", "Bearer $token").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            request("wrong").statusCode() shouldBe 401
            reads shouldBe 0
            val response = request("online-fixture-token")
            response.statusCode() shouldBe 200
            response.headers().firstValue("Cache-Control").orElse("") shouldBe "no-store"
            val body = ObjectMapper().readTree(response.body())
            body["schema"].asText() shouldBe "proxyarc.online.v1"
            body["scope"].asText() shouldBe "proxy"
            body["complete"].asBoolean() shouldBe true
            body["sampled_at"].asLong() shouldBe 1234
            body["count"].asInt() shouldBe 1
            body["players"][0]["name"].asText() shouldBe "CodexQA1"
            available = false
            request("online-fixture-token").statusCode() shouldBe 503
        } finally {
            server.stop()
            directory.toFile().deleteRecursively()
        }
    }
})
