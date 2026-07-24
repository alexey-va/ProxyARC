package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.dispatch.RouteDedup

class RouteDedupTest : FreeSpec({
    beforeEach { RouteDedup.clear() }
    afterEach { RouteDedup.clear() }

    "suppresses only an immediate duplicate delivery" {
        RouteDedup.isDuplicate("bug:player:message", nowMs = 10_000L) shouldBe false
        RouteDedup.isDuplicate("bug:player:message", nowMs = 10_100L) shouldBe true
        RouteDedup.isDuplicate("bug:player:message", nowMs = 15_000L) shouldBe false
    }

    "does not mix independent messages" {
        RouteDedup.isDuplicate("bug:player:first", nowMs = 10_000L) shouldBe false
        RouteDedup.isDuplicate("bug:player:second", nowMs = 10_100L) shouldBe false
    }
})
