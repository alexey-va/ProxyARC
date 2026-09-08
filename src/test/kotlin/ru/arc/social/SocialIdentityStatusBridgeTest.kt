package ru.arc.social

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.redis.network.RedisReplyRejection
import ru.arc.redis.network.RedisRequestReplyChannel
import ru.arc.redis.network.RedisRequestResult
import ru.arc.redis.network.RedisRequestTimeoutHandle
import ru.arc.redis.network.RedisRequestTimeoutScheduler
import ru.arc.velocity.Velocity
import java.util.UUID

class SocialIdentityStatusBridgeTest : FreeSpec({
    val playerId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val codec = SocialIdentityStatusWire.codec()

    afterTest {
        Velocity.discordBot = null
        Velocity.telegramBot = null
    }

    "provider readiness is fail-closed and never reports unlinked when a bot is unavailable" {
        Velocity.discordBot = null
        Velocity.telegramBot = null

        val redis = InMemoryRedis(ServerIdentity { "proxy" })
        val bridge = SocialIdentityStatusBridge(redis)
        bridge.start()
        redis.simulateExternalMessage(
            SocialIdentityStatusWire.CHANNEL,
            codec.encode(SocialIdentityStatusWire.request("arcranks:req", playerId)),
            "survival",
        )

        val raw = redis.getPublishedMessages().single().message
        raw.contains("discordUserId") shouldBe false
        raw.contains("telegramUserId") shouldBe false
        val reply = codec.decode(raw)
        reply.discord shouldBe SocialIdentityStatusWire.UNAVAILABLE
        reply.telegram shouldBe SocialIdentityStatusWire.UNAVAILABLE
        bridge.close()
    }

    "wire preserves linked unlinked and unknown without conflating states" {
        val request = SocialIdentityStatusWire.request("arcranks:req", playerId)
        listOf(SocialIdentityStatusWire.LINKED, SocialIdentityStatusWire.UNLINKED,
            SocialIdentityStatusWire.UNAVAILABLE).forEach { state ->
            val reply = SocialIdentityStatusWire.reply(request, state, state)
            codec.decode(codec.encode(reply)) shouldBe reply
        }
    }

    "reply correlation rejects another player's status and accepts the matching one" {
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        val scheduler = ManualTimeoutScheduler()
        val rejected = mutableListOf<RedisReplyRejection>()
        val channel = RedisRequestReplyChannel(
            redis = redis,
            channel = SocialIdentityStatusWire.CHANNEL,
            codec = codec,
            originAllowed = { it == "proxy" },
            requestId = SocialIdentityStatusWire::requestId,
            replyTo = SocialIdentityStatusWire::replyTo,
            replyAllowed = { request, reply, origin ->
                origin == "proxy" && reply.isReply() &&
                    reply.replyTo == request.requestId &&
                    reply.requestId == request.requestId &&
                    reply.playerId == request.playerId
            },
            timeoutMillis = 500,
            maxPending = 2,
            timeoutScheduler = scheduler,
            onMessage = { _, _ -> },
            onReplyRejected = rejected::add,
        )
        val request = SocialIdentityStatusWire.request("arcranks:req", playerId)
        val future = channel.request(request)
        val wrongPlayer = UUID.fromString("22222222-2222-2222-2222-222222222222")
        redis.simulateExternalMessage(
            SocialIdentityStatusWire.CHANNEL,
            codec.encode(
                SocialIdentityStatusWire(
                    kind = SocialIdentityStatusWire.KIND,
                    requestId = request.requestId,
                    replyTo = request.requestId,
                    playerId = wrongPlayer.toString(),
                    discord = SocialIdentityStatusWire.LINKED,
                    telegram = SocialIdentityStatusWire.UNAVAILABLE,
                ),
            ),
            "proxy",
        )
        channel.pendingCount() shouldBe 1
        rejected shouldContainExactly listOf(RedisReplyRejection.REPLY_POLICY_REJECTED)

        redis.simulateExternalMessage(
            SocialIdentityStatusWire.CHANNEL,
            codec.encode(
                SocialIdentityStatusWire(
                    kind = SocialIdentityStatusWire.KIND,
                    requestId = request.requestId,
                    replyTo = request.requestId,
                    playerId = playerId.toString(),
                    discord = SocialIdentityStatusWire.LINKED,
                    telegram = SocialIdentityStatusWire.UNAVAILABLE,
                ),
            ),
            "proxy",
        )
        future.get() shouldBe RedisRequestResult.Reply(
            SocialIdentityStatusWire(
                SocialIdentityStatusWire.KIND,
                request.requestId,
                request.requestId,
                playerId.toString(),
                SocialIdentityStatusWire.LINKED,
                SocialIdentityStatusWire.UNAVAILABLE,
            ),
            "proxy",
        )
        channel.pendingCount() shouldBe 0
        scheduler.cancelledCount() shouldBe 1
        channel.close()
    }
})

private class ManualTimeoutScheduler : RedisRequestTimeoutScheduler {
    private val handles = mutableListOf<Entry>()

    private class Entry(val action: () -> Unit) {
        var cancelled = false
    }

    override fun schedule(delayMillis: Long, action: () -> Unit): RedisRequestTimeoutHandle {
        val entry = Entry(action)
        handles += entry
        return RedisRequestTimeoutHandle { entry.cancelled = true }
    }

    fun cancelledCount(): Int = handles.count { it.cancelled }
}
