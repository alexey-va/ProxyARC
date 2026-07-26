package ru.arc.core.modules

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.Common
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.xserver.JoinMessages
import java.util.concurrent.TimeUnit

class JoinMessagesModuleTest : FreeSpec({
    afterTest {
        JoinMessagesModule.shutdown()
    }

    "loads the Paper-compatible entity schema from shared Redis storage" {
        val redis = testRedis()
        redis.setHash(
            STORAGE_KEY,
            mapOf(
                "Alex" to
                    """
                    {
                      "player":"Alex",
                      "joinMessages":["joined"],
                      "leaveMessages":["left"],
                      "timestamp":42
                    }
                    """.trimIndent(),
            ),
        )

        JoinMessagesModule.start(redis)
        val loaded = JoinMessagesModule.loadAsync("Alex").get(2, TimeUnit.SECONDS)

        loaded?.randomJoinMessage() shouldBe "joined"
        loaded?.randomLeaveMessage() shouldBe "left"
        redis.listenerCount(UPDATE_CHANNEL) shouldBe 1
    }

    "applies the shared repository pubsub wire format and unregisters on shutdown" {
        val redis = testRedis()
        redis.setHash(
            STORAGE_KEY,
            mapOf(
                "Alex" to
                    Common.gson.toJson(
                        JoinMessages("Alex").apply { joinMessages = setOf("old") },
                    ),
            ),
        )
        JoinMessagesModule.start(redis)
        val cached = JoinMessagesModule.loadAsync("Alex").get(2, TimeUnit.SECONDS)
        cached?.randomJoinMessage() shouldBe "old"

        val updated = JoinMessages("Alex").apply { joinMessages = setOf("new") }
        val updateMessage =
            mapOf(
                "type" to "UPDATE",
                "id" to "Alex",
                "data" to Common.gson.toJson(updated),
            )
        redis.publish(UPDATE_CHANNEL, Common.gson.toJson(updateMessage))

        awaitUntil {
            JoinMessagesModule.loadAsync("Alex")
                .get(2, TimeUnit.SECONDS)
                ?.randomJoinMessage() == "new"
        }
        cached?.randomJoinMessage() shouldBe "new"

        JoinMessagesModule.shutdown()
        redis.listenerCount(UPDATE_CHANNEL) shouldBe 0
    }
})

private fun testRedis(): InMemoryRedis =
    InMemoryRedis(ServerIdentity { "proxy-test" })

private fun awaitUntil(
    timeoutMillis: Long = 2_000,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition() && System.currentTimeMillis() < deadline) {
        Thread.sleep(10)
    }
    condition() shouldBe true
}

private const val STORAGE_KEY = "arc.join_messages"
private const val UPDATE_CHANNEL = "arc.join_messages_update"
