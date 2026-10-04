package ru.arc.metrics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class ResourcePackJourneyClockTest : StringSpec({
    "backend and proxy pack timings keep distinct measurement baselines and reset after completion" {
        val clock = ResourcePackJourneyClock()
        val player = UUID.randomUUID()
        val pack = UUID.randomUUID()
        clock.sent(player, pack, 1_000)
        clock.status(player, pack, "ACCEPTED", 2_000)["elapsedMs"] shouldBe "1000"
        clock.status(player, pack, "DOWNLOADED", 7_000)["downloadMs"] shouldBe "5000"
        val completed = clock.status(player, pack, "SUCCESSFUL", 9_000)
        completed["timingBasis"] shouldBe "backend_send"
        completed["elapsedMs"] shouldBe "8000"
        completed["applyMs"] shouldBe "2000"
        clock.status(player, pack, "ACCEPTED", 20_000)["timingBasis"] shouldBe "accepted"
        clock.status(player, pack, "SUCCESSFUL", 25_000)["elapsedMs"] shouldBe "5000"
        clock.status(player, pack, "DECLINED", 30_000)["elapsedMs"] shouldBe null
    }
})
