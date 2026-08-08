package ru.arc.rtp

import com.velocitypowered.api.proxy.ConnectionRequestBuilder
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
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
        fun config(transferMessage: String = ""): ProxyRtpConfig {
            val raw = Config(Files.createTempDirectory("proxy-rtp-manager-"), "rtp.yml")
            raw.setString("target-server", "survival")
            raw.setString("default-world", "survival")
            raw.setString("transfer-message", transferMessage)
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

        "keeps a bare request in the current Bukkit world on survival" {
            val proxy = mockk<ProxyServer>()
            val registered = mockk<RegisteredServer>()
            val connection = mockk<ServerConnection>()
            val player = mockk<Player>(relaxed = true)
            val playerId = UUID.randomUUID()
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

            RtpRequestManager(proxy, config(), clockMillis = { 1000L }).request(player, null)

            decoded?.worldName shouldBe NetworkRtpRequest.CURRENT_WORLD
            decoded?.mode shouldBe NetworkRtpMode.REGULAR
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

        "does not send a transfer message when it is empty" {
            val proxy = mockk<ProxyServer>()
            val registered = mockk<RegisteredServer>()
            val player = mockk<Player>(relaxed = true)
            val connectionRequest = mockk<ConnectionRequestBuilder>()
            val playerId = UUID.randomUUID()

            every { proxy.getServer("survival") } returns Optional.of(registered)
            every { player.uniqueId } returns playerId
            every { player.username } returns "TestPlayer"
            every { player.currentServer } returns Optional.empty()
            every { player.createConnectionRequest(registered) } returns connectionRequest
            every { connectionRequest.connect() } returns CompletableFuture()

            RtpRequestManager(proxy, config(transferMessage = "")).request(player, "survival")

            verify(exactly = 0) { player.sendMessage(any()) }
        }

        "sends a configured transfer message" {
            val proxy = mockk<ProxyServer>()
            val registered = mockk<RegisteredServer>()
            val player = mockk<Player>(relaxed = true)
            val connectionRequest = mockk<ConnectionRequestBuilder>()
            val playerId = UUID.randomUUID()

            every { proxy.getServer("survival") } returns Optional.of(registered)
            every { player.uniqueId } returns playerId
            every { player.username } returns "TestPlayer"
            every { player.currentServer } returns Optional.empty()
            every { player.createConnectionRequest(registered) } returns connectionRequest
            every { connectionRequest.connect() } returns CompletableFuture()

            RtpRequestManager(proxy, config(transferMessage = "<gray>Переход…")).request(player, "survival")

            verify(exactly = 1) { player.sendMessage(any()) }
        }

        "uses the one-second fallback when the backend-ready signal is missing" {
            val proxy = mockk<ProxyServer>()
            val registered = mockk<RegisteredServer>()
            val connection = mockk<ServerConnection>()
            val player = mockk<Player>(relaxed = true)
            val connectionRequest = mockk<ConnectionRequestBuilder>()
            val future = CompletableFuture<ConnectionRequestBuilder.Result>()
            val result = mockk<ConnectionRequestBuilder.Result>()
            val event = mockk<ServerPostConnectEvent>()
            val playerId = UUID.randomUUID()
            var currentServer: Optional<ServerConnection> = Optional.empty()
            var scheduledTicks: Long? = null
            var scheduledTask: Runnable? = null

            every { proxy.getServer("survival") } returns Optional.of(registered)
            every { player.uniqueId } returns playerId
            every { player.username } returns "TestPlayer"
            every { player.currentServer } answers { currentServer }
            every { player.createConnectionRequest(registered) } returns connectionRequest
            every { connectionRequest.connect() } returns future
            every { result.isSuccessful } returns true
            every { connection.serverInfo } returns ServerInfo("survival", InetSocketAddress("127.0.0.1", 25565))
            every { connection.sendPluginMessage(any(), any<ByteArray>()) } returns true
            every { event.player } returns player

            val manager =
                RtpRequestManager(
                    proxy,
                    config(),
                    clockMillis = { 1000L },
                    scheduleLater = { ticks, task ->
                        scheduledTicks = ticks
                        scheduledTask = task
                    },
                )
            manager.request(player, "survival")
            currentServer = Optional.of(connection)
            future.complete(result)
            manager.onServerPostConnect(event)

            scheduledTicks shouldBe 20L
            verify(exactly = 0) { connection.sendPluginMessage(any(), any<ByteArray>()) }
            manager.pendingCount() shouldBe 1

            scheduledTask?.run()

            verify(exactly = 1) { connection.sendPluginMessage(any(), any<ByteArray>()) }
            manager.pendingCount() shouldBe 0
        }

        "delivers immediately when the target backend reports the player ready" {
            val proxy = mockk<ProxyServer>()
            val registered = mockk<RegisteredServer>()
            val connection = mockk<ServerConnection>()
            val wrongConnection = mockk<ServerConnection>(relaxed = true)
            val player = mockk<Player>(relaxed = true)
            val connectionRequest = mockk<ConnectionRequestBuilder>()
            val future = CompletableFuture<ConnectionRequestBuilder.Result>()
            val result = mockk<ConnectionRequestBuilder.Result>()
            val playerId = UUID.randomUUID()
            var currentServer: Optional<ServerConnection> = Optional.empty()
            var scheduledTicks: Long? = null

            every { proxy.getServer("survival") } returns Optional.of(registered)
            every { player.uniqueId } returns playerId
            every { player.username } returns "TestPlayer"
            every { player.currentServer } answers { currentServer }
            every { player.createConnectionRequest(registered) } returns connectionRequest
            every { connectionRequest.connect() } returns future
            every { result.isSuccessful } returns true
            every { connection.player } returns player
            every { connection.serverInfo } returns ServerInfo("survival", InetSocketAddress("127.0.0.1", 25565))
            every { connection.sendPluginMessage(any(), any<ByteArray>()) } returns true
            every { wrongConnection.player } returns player
            every { wrongConnection.serverInfo } returns ServerInfo("spawn", InetSocketAddress("127.0.0.1", 25566))

            val manager =
                RtpRequestManager(
                    proxy,
                    config(),
                    clockMillis = { 1000L },
                    scheduleLater = { ticks, _ -> scheduledTicks = ticks },
                )
            manager.request(player, "survival")
            currentServer = Optional.of(connection)
            future.complete(result)

            scheduledTicks shouldBe 20L
            verify(exactly = 0) { connection.sendPluginMessage(any(), any<ByteArray>()) }

            manager.backendReady(wrongConnection)

            verify(exactly = 0) { connection.sendPluginMessage(any(), any<ByteArray>()) }
            manager.pendingCount() shouldBe 1

            manager.backendReady(connection)

            verify(exactly = 1) { connection.sendPluginMessage(any(), any<ByteArray>()) }
            manager.pendingCount() shouldBe 0
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
