package ru.arc.discord

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import com.neovisionaries.ws.client.WebSocketFactory
import net.dv8tion.jda.api.JDABuilder
import okhttp3.OkHttpClient
import ru.arc.config.Config
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class DiscordProxyTest : FreeSpec({
    "reads Tinyproxy settings from discord config" {
        val directory = createTempDirectory("discord-proxy")
        Files.writeString(
            directory.resolve("discord.yml"),
            """
            http-proxy:
              enabled: true
              host: 185.242.106.81
              port: 8888
            """.trimIndent(),
        )

        DiscordProxySettings.from(Config(directory, "discord.yml")) shouldBe
            DiscordProxySettings(
                enabled = true,
                host = "185.242.106.81",
                port = 8888,
            )
    }

    "applies proxy to both REST and Gateway clients" {
        val builder = mockk<JDABuilder>(relaxed = true)
        val httpClient = slot<OkHttpClient.Builder>()
        val webSocket = slot<WebSocketFactory>()
        every { builder.setHttpClientBuilder(capture(httpClient)) } returns builder
        every { builder.setWebsocketFactory(capture(webSocket)) } returns builder
        val settings = DiscordProxySettings(true, "185.242.106.81", 8888)

        settings.applyTo(builder)

        verify(exactly = 1) { builder.setHttpClientBuilder(any()) }
        verify(exactly = 1) { builder.setWebsocketFactory(any()) }
        val httpAddress = httpClient.captured.build().proxy?.address() as InetSocketAddress
        httpAddress.hostString shouldBe "185.242.106.81"
        httpAddress.port shouldBe 8888
        webSocket.captured.proxySettings.host shouldBe "185.242.106.81"
        webSocket.captured.proxySettings.port shouldBe 8888
    }

    "rejects enabled proxy without a host" {
        val directory = createTempDirectory("discord-proxy-invalid")
        Files.writeString(
            directory.resolve("discord.yml"),
            """
            http-proxy:
              enabled: true
              host: ""
              port: 8888
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            DiscordProxySettings.from(Config(directory, "discord.yml"))
        }
    }
})
