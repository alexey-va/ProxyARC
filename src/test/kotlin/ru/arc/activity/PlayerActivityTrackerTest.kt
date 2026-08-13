package ru.arc.activity

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.activity.PlayerActivityStore
import java.util.UUID

class PlayerActivityTrackerTest :
    StringSpec({
        "seeds online players and advances them with one heartbeat timestamp" {
            var now = 1_000L
            val redis = InMemoryRedis()
            val first = UUID.fromString("5b8e06f1-11ef-4d83-a290-2238632a16d1")
            val second = UUID.fromString("3fc10291-2b9e-45e4-b3ce-b0e2fcce8aec")
            val tracker =
                PlayerActivityTracker(
                    redis = redis,
                    logger = LoggerFactory.getLogger("PlayerActivityTrackerTest"),
                    clockMillis = { now },
                )

            tracker.start(listOf(first)).get()
            now = 2_000L
            tracker.markSeenAll(listOf(first, second, first)).get()

            PlayerActivityStore(redis).load().get().let { snapshot ->
                snapshot.coverageStartedAt shouldBe 1_000L
                snapshot.lastSeen shouldBe mapOf(first to 2_000L, second to 2_000L)
            }
        }

        "establishes coverage even when nobody is online" {
            val redis = InMemoryRedis()
            val tracker =
                PlayerActivityTracker(
                    redis = redis,
                    logger = LoggerFactory.getLogger("PlayerActivityTrackerTest"),
                    clockMillis = { 5_000L },
                )

            tracker.start(emptyList()).get()

            PlayerActivityStore(redis).load().get().coverageStartedAt shouldBe 5_000L
        }

        "retries coverage initialization after a transient Redis read failure" {
            val redis = InMemoryRedis().apply { failOnLoad = true }
            val playerId = UUID.fromString("5b8e06f1-11ef-4d83-a290-2238632a16d1")
            val tracker =
                PlayerActivityTracker(
                    redis = redis,
                    logger = LoggerFactory.getLogger("PlayerActivityTrackerTest"),
                    clockMillis = { 7_000L },
                )

            runCatching { tracker.start(emptyList()).get() }
            redis.failOnLoad = false
            tracker.markSeen(playerId).get()

            PlayerActivityStore(redis).load().get().let { snapshot ->
                snapshot.coverageStartedAt shouldBe 7_000L
                snapshot.lastSeen[playerId] shouldBe 7_000L
            }
        }
    })
