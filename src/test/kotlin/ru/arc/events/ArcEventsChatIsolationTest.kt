package ru.arc.events

import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.ChannelMessageSource
import net.kyori.adventure.text.Component
import com.velocitypowered.api.proxy.server.ServerInfo
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.InetSocketAddress
import java.util.Optional
import java.util.UUID
import ru.arc.velocity.Velocity

class ArcEventsChatIsolationTest : FreeSpec({
    fun backend(name: String, player: Player): ServerConnection =
        mockk<ServerConnection>().also { connection ->
            every { connection.player } returns player
            every { connection.serverInfo } returns ServerInfo(name, InetSocketAddress("127.0.0.1", 25565))
        }

    fun message(
        source: ChannelMessageSource,
        target: Player,
        payload: ByteArray,
    ): PluginMessageEvent = mockk<PluginMessageEvent>(relaxed = true).also { event ->
        every { event.identifier } returns ArcEventsChatIsolation.CHANNEL
        every { event.source } returns source
        every { event.target } returns target
        every { event.data } returns payload
    }

    "accepts only a current parkour carrier and expires the lease" {
        var now = 0L
        val isolation = ArcEventsChatIsolation(nowNanos = { now })
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        val connection = backend("parkour", player)
        every { player.uniqueId } returns id
        every { player.currentServer } returns Optional.of(connection)

        val event = message(
            source = connection,
            target = player,
            payload = byteArrayOf(ArcEventsChatIsolation.PROTOCOL_VERSION, ArcEventsChatIsolation.ACTIVE),
        )
        isolation.onPluginMessage(event)

        isolation.isIsolated(player) shouldBe true
        verify { event.result = PluginMessageEvent.ForwardResult.handled() }

        now = 15_000_000_000L
        isolation.isIsolated(player) shouldBe false
    }

    "rejects stale or malformed origins without renewing" {
        var now = 100L
        val isolation = ArcEventsChatIsolation(nowNanos = { now })
        val player = mockk<Player>()
        val other = mockk<Player>()
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        every { other.uniqueId } returns UUID.randomUUID()
        val spawn = backend("spawn", player)
        val parkour = backend("parkour", player)
        val staleParkour = backend("parkour", player)
        every { player.currentServer } returns Optional.of(spawn)

        isolation.onPluginMessage(
            message(parkour, player, byteArrayOf(ArcEventsChatIsolation.PROTOCOL_VERSION, ArcEventsChatIsolation.ACTIVE)),
        )
        isolation.onPluginMessage(
            message(spawn, player, byteArrayOf(ArcEventsChatIsolation.PROTOCOL_VERSION, ArcEventsChatIsolation.ACTIVE)),
        )
        every { player.currentServer } returns Optional.of(parkour)
        isolation.onPluginMessage(
            message(staleParkour, player, byteArrayOf(ArcEventsChatIsolation.PROTOCOL_VERSION, ArcEventsChatIsolation.ACTIVE)),
        )
        isolation.onPluginMessage(
            message(parkour, other, byteArrayOf(ArcEventsChatIsolation.PROTOCOL_VERSION, ArcEventsChatIsolation.ACTIVE)),
        )
        isolation.onPluginMessage(message(parkour, player, byteArrayOf(ArcEventsChatIsolation.PROTOCOL_VERSION)))

        isolation.isIsolated(player) shouldBe false
    }

    "drops the lease when the player reconnects to a same-named backend" {
        val isolation = ArcEventsChatIsolation(nowNanos = { 0L })
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        val firstConnection = backend("parkour", player)
        val replacementConnection = backend("parkour", player)
        every { player.uniqueId } returns id
        every { player.currentServer } returns Optional.of(firstConnection)

        isolation.onPluginMessage(
            message(firstConnection, player, byteArrayOf(1, 1)),
        )
        isolation.isIsolated(player) shouldBe true

        every { player.currentServer } returns Optional.of(replacementConnection)
        isolation.isIsolated(player) shouldBe false
    }

    "filters active players while preserving console delivery" {
        val isolation = ArcEventsChatIsolation(nowNanos = { 0L })
        val player = mockk<Player>(relaxed = true)
        val console = mockk<CommandSource>(relaxed = true)
        val connection = backend("parkour", player)
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.currentServer } returns Optional.of(connection)
        val component = mockk<Component>()
        isolation.onPluginMessage(message(connection, player, byteArrayOf(1, 1)))

        Velocity.eventsChatIsolation = isolation
        try {
            Velocity.sendMessageTo(player, component) shouldBe false
            verify(exactly = 0) { player.sendMessage(component) }
            Velocity.sendMessageTo(console, component) shouldBe true
            verify(exactly = 1) { console.sendMessage(component) }
        } finally {
            Velocity.eventsChatIsolation = null
        }
    }

    "inactive, transition, disconnect and clear remove the lease" {
        val isolation = ArcEventsChatIsolation(nowNanos = { 0L })
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        val connection = backend("parkour", player)
        every { player.uniqueId } returns id
        every { player.currentServer } returns Optional.of(connection)

        isolation.onPluginMessage(
            message(connection, player, byteArrayOf(1, 1)),
        )
        isolation.isIsolated(player) shouldBe true
        val connected = mockk<ServerConnectedEvent>()
        every { connected.player } returns player
        isolation.onServerConnected(connected)
        isolation.isIsolated(player) shouldBe false

        isolation.onPluginMessage(
            message(connection, player, byteArrayOf(1, 1)),
        )
        val disconnected = mockk<DisconnectEvent>()
        every { disconnected.player } returns player
        isolation.onDisconnect(disconnected)
        isolation.isIsolated(player) shouldBe false

        isolation.onPluginMessage(
            message(connection, player, byteArrayOf(1, 0)),
        )
        isolation.isIsolated(player) shouldBe false

        isolation.onPluginMessage(
            message(connection, player, byteArrayOf(1, 1)),
        )
        isolation.clear(id)
        isolation.isIsolated(player) shouldBe false

        isolation.onPluginMessage(
            message(connection, player, byteArrayOf(1, 1)),
        )
        isolation.clear()
        isolation.recipients(listOf(player)) shouldContainExactly listOf(player)
    }

    "reload revokes leases from servers removed from the allowlist" {
        var allowed = setOf("parkour")
        val isolation = ArcEventsChatIsolation(
            nowNanos = { 0L },
            settings = { ArcEventsChatSettings(allowedServers = allowed) },
        )
        val player = mockk<Player>()
        val connection = backend("parkour", player)
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.currentServer } returns Optional.of(connection)

        isolation.onPluginMessage(message(connection, player, byteArrayOf(1, 1)))
        isolation.isIsolated(player) shouldBe true

        allowed = setOf("events")
        isolation.reload()
        isolation.isIsolated(player) shouldBe false
    }
})
