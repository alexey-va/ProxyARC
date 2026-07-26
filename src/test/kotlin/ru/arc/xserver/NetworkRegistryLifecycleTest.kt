package ru.arc.xserver

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.ChannelListener
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import ru.arc.velocity.Velocity

class NetworkRegistryLifecycleTest : FreeSpec({
    afterTest {
        Velocity.dataFolder = null
        Velocity.llmClient = null
    }

    "repeated init and close should own auction subscriptions" {
        Velocity.dataFolder = null
        val redis = InMemoryRedis()
        val registry = NetworkRegistry(redis)

        registry.init()
        registry.init()

        redis.listenerCount("arc.auction_items") shouldBe 1
        redis.listenerCount("arc.auction_items_all") shouldBe 1

        registry.close()

        redis.listenerCount("arc.auction_items") shouldBe 0
        redis.listenerCount("arc.auction_items_all") shouldBe 0
    }

    "partial registration failure should roll back installed listener" {
        Velocity.dataFolder = null
        val redis = FailingRegistrationRedis(failAt = 2)
        val registry = NetworkRegistry(redis)

        shouldThrow<IllegalStateException> {
            registry.init()
        }

        redis.delegate.listenerCount("arc.auction_items") shouldBe 0
        redis.delegate.listenerCount("arc.auction_items_all") shouldBe 0
    }
})

private class FailingRegistrationRedis(
    private val failAt: Int,
    val delegate: InMemoryRedis = InMemoryRedis(),
) : RedisOperations by delegate {
    private var registrations = 0

    override fun registerChannelUnique(channel: String, listener: ChannelListener) {
        registrations++
        if (registrations == failAt) throw IllegalStateException("registration failed")
        delegate.registerChannelUnique(channel, listener)
    }
}
