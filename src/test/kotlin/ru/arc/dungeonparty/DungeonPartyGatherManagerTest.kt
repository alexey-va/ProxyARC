package ru.arc.dungeonparty

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.util.UUID

class DungeonPartyGatherManagerTest : FreeSpec({
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
