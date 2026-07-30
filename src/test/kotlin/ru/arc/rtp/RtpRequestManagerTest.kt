package ru.arc.rtp

import com.velocitypowered.api.proxy.ConnectionRequestBuilder
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.config.Config
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RtpRequestManagerTest :
    FreeSpec({
        fun config(): ProxyRtpConfig {
            val raw = Config(Files.createTempDirectory("proxy-rtp-manager-"), "rtp.yml")
            raw.setString("target-server", "survival")
            raw.setString("default-world", "survival")
            raw.setStringList("allowed-worlds", listOf("survival", "mining", "vanilla"))
            raw.setLong("request-timeout-seconds", 20L)
            return ProxyRtpConfig(raw)
        }

        "delivers a typed request over the current survival connection" {
            val proxy = mockk<ProxyServer>()
            val registered = mockk<RegisteredServer>()
            val connection = mockk<ServerConnection>()
            val player = mockk<Player>(relaxed = true)
            val playerId = UUID.randomUUID()
            val requestId = UUID.randomUUID()
            var decoded: NetworkRtpRequest? = null

            every { proxy.getServer("survival") } returns Optional.of(registered)
            every { player.uniqueId } returns playerId
            every { player.username } returns "TestPlayer"
            every { player.currentServer } returns Optional.of(connection)
            every { connection.serverInfo } returns ServerInfo("survival", InetSocketAddress("127.0.0.1", 25565))
            every { connection.sendPluginMessage(any(), any<ByteArray>()) } answers {
                decoded = NetworkRtpRequest.decode(secondArg())
                true
            }

            val manager = RtpRequestManager(proxy, config(), clockMillis = { 1000L }, requestIds = { requestId })
            manager.request(player, "mining")

            decoded shouldBe
                NetworkRtpRequest(
                    requestId,
                    playerId,
                    "mining",
                    "survival",
                    NetworkRtpMode.REGULAR,
                )
            manager.pendingCount() shouldBe 0
        }

        "coalesces a second request while the server transfer is pending" {
            val proxy = mockk<ProxyServer>()
            val registered = mockk<RegisteredServer>()
            val player = mockk<Player>(relaxed = true)
            val connectionRequest = mockk<ConnectionRequestBuilder>()
            val future = CompletableFuture<ConnectionRequestBuilder.Result>()
            val playerId = UUID.randomUUID()

            every { proxy.getServer("survival") } returns Optional.of(registered)
            every { player.uniqueId } returns playerId
            every { player.username } returns "TestPlayer"
            every { player.currentServer } returns Optional.empty()
            every { player.createConnectionRequest(registered) } returns connectionRequest
            every { connectionRequest.connect() } returns future

            val manager = RtpRequestManager(proxy, config(), clockMillis = { 1000L })
            manager.request(player, "survival")
            manager.request(player, "vanilla")

            manager.pendingCount() shouldBe 1
            manager.pendingRequest(playerId)?.mode shouldBe NetworkRtpMode.FIRST_ENTRY
            verify(exactly = 1) { player.createConnectionRequest(registered) }
        }

        "consumes the trusted channel at the proxy boundary" {
            val proxy = mockk<ProxyServer>()
            val event = mockk<PluginMessageEvent>(relaxed = true)
            every { event.identifier } returns RtpRequestManager.CHANNEL

            RtpRequestManager(proxy, config()).onPluginMessage(event)

            verify {
                event.result = PluginMessageEvent.ForwardResult.handled()
            }
        }
    })
