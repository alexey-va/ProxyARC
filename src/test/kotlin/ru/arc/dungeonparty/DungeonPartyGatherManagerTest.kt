package ru.arc.dungeonparty

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.ServerConnection
import java.util.Optional
import java.util.UUID

class DungeonPartyGatherManagerTest : FreeSpec({
    "gathers on the registered spawn backend instead of the retired classic alias" {
        val leaderId = UUID.randomUUID()
        val leader = mockk<Player>(relaxed = true)
        val connection = mockk<ServerConnection>(relaxed = true)
        val spawn = mockk<RegisteredServer>(relaxed = true)
        val proxy = mockk<ProxyServer>()
        val scheduled = mutableListOf<Runnable>()
        val manager = DungeonPartyGatherManager(proxy, scheduleLater = { _, task -> scheduled += task })
        val request = DungeonPartyGatherRequest(UUID.randomUUID(), leaderId, listOf(leaderId))
        every { leader.uniqueId } returns leaderId
        every { leader.currentServer } returns Optional.of(connection)
        every { connection.player } returns leader
        every { connection.serverInfo.name } returns "spawn"
        every { proxy.getServer("spawn") } returns Optional.of(spawn)
        every { proxy.getPlayer(leaderId) } returns Optional.of(leader)
        val event = mockk<PluginMessageEvent>(relaxed = true)
        every { event.identifier } returns DungeonPartyGatherManager.REQUEST_CHANNEL
        every { event.source } returns connection
        every { event.target } returns leader
        every { event.data } returns request.encode()

        manager.onPluginMessage(event)

        scheduled.size shouldBe 1
        verify(exactly = 1) { proxy.getServer("spawn") }
        verify(exactly = 0) { leader.createConnectionRequest(any<RegisteredServer>()) }
        manager.shutdown()
    }

    "selects the leader and at most four online teammates in team order" {
        val leader = UUID.randomUUID()
        val members = List(7) { UUID.randomUUID() }
        val request = DungeonPartyGatherRequest(UUID.randomUUID(), leader, listOf(leader) + members)
        val online = setOf(leader, members[1], members[2], members[3], members[4], members[5])
        val manager = DungeonPartyGatherManager(mockk(relaxed = true))

        manager.selectOnlineRoster(request, online::contains) shouldBe
            listOf(leader, members[1], members[2], members[3], members[4])
    }

    "returns no roster when the verified leader went offline" {
        val leader = UUID.randomUUID()
        val member = UUID.randomUUID()
        val request = DungeonPartyGatherRequest(UUID.randomUUID(), leader, listOf(leader, member))
        val manager = DungeonPartyGatherManager(mockk(relaxed = true))

        manager.selectOnlineRoster(request) { it == member } shouldBe emptyList()
    }
})
