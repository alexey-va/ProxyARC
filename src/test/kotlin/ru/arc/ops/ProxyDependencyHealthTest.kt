package ru.arc.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import ru.arc.discord.DiscordTransportHealth
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthRegistry
import ru.arc.observability.RuntimeHealthState
import ru.arc.redis.RedisManager
import ru.arc.redis.RedisOperation
import ru.arc.redis.RedisOperationResult
import ru.arc.redis.RedisTelemetrySink
import ru.arc.redis.RedisConnection
import ru.arc.redis.ServerIdentity
import redis.clients.jedis.JedisPooled
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException

class ProxyDependencyHealthTest : FreeSpec({
    "connected Redis with failed publication is not ready; PING cannot heal it" {
        val health = ProxyRedisHealth { 1L }
        health.onOperation(RedisOperation.PUBLISH, RedisOperationResult.FAILURE, 0)
        health.onOperation(RedisOperation.HEALTH_CHECK, RedisOperationResult.SUCCESS, 0)
        val registry = RuntimeHealthRegistry("test").apply {
            register("runtime") { withDependencyHealth(RuntimeHealthContribution(), health.dependencies(true, true)) }
            markReady()
        }
        registry.snapshot().ready shouldBe false
        registry.snapshot().state shouldBe RuntimeHealthState.DEGRADED
        health.onOperation(RedisOperation.PUBLISH, RedisOperationResult.SUCCESS, 0)
        registry.snapshot().ready shouldBe true
    }

    "write failure survives unrelated successful publication" {
        val health = ProxyRedisHealth { 1L }
        health.onOperation(RedisOperation.SAVE_MAP, RedisOperationResult.FAILURE, 0)
        health.onOperation(RedisOperation.PUBLISH, RedisOperationResult.SUCCESS, 0)
        health.dependencies(true, true)["redis_operations"] shouldBe false
        health.onOperation(RedisOperation.SAVE_MAP, RedisOperationResult.SUCCESS, 0)
        health.dependencies(true, true)["redis_operations"] shouldBe true
    }

    "missing and stale publication evidence fails closed" {
        var now = 0L
        val health = ProxyRedisHealth { now }
        health.dependencies(true, true)["redis_publish_fresh"] shouldBe false
        health.onOperation(RedisOperation.PUBLISH, RedisOperationResult.SUCCESS, 0)
        health.dependencies(true, true)["redis_publish_fresh"] shouldBe true
        now = 46_000_000_000L
        health.dependencies(true, true)["redis_publish_fresh"] shouldBe false
    }

    "dependency failure cannot replace module DOWN or STARTING" {
        listOf(RuntimeHealthState.DOWN, RuntimeHealthState.STARTING, RuntimeHealthState.DEGRADED).forEach {
            withDependencyHealth(RuntimeHealthContribution(state = it), mapOf("redis" to false)).state shouldBe it
        }
        withDependencyHealth(RuntimeHealthContribution(), mapOf("redis" to false)).state shouldBe RuntimeHealthState.DEGRADED
    }

    "Redis telemetry preserves failures across exporter reload and fences replaced managers" {
        val manager = RedisManager(
            RedisConnection("unused", 1),
            ServerIdentity { "test" },
            poolFactory = { mockk<JedisPooled>(relaxed = true) },
        )
        val metrics = mockk<RedisTelemetrySink>(relaxed = true)
        val old = ProxyRedisHealthBinding.install(manager, metrics)
        old.onOperation(RedisOperation.PUBLISH, RedisOperationResult.SUCCESS, 0)
        verify { metrics.onOperation(RedisOperation.PUBLISH, RedisOperationResult.SUCCESS, 0) }
        ProxyRedisHealthBinding.dependencies(manager)["redis_publish_fresh"] shouldBe true
        old.onOperation(RedisOperation.SAVE_MAP, RedisOperationResult.FAILURE, 0)
        val current = ProxyRedisHealthBinding.install(manager, null)
        ProxyRedisHealthBinding.dependencies(manager)["redis_operations"] shouldBe false
        current.onOperation(RedisOperation.SAVE_MAP, RedisOperationResult.SUCCESS, 0)
        current.onOperation(RedisOperation.PUBLISH, RedisOperationResult.SUCCESS, 0)
        ProxyRedisHealthBinding.dependencies(manager)["redis_publish_fresh"] shouldBe true
        val replacement = RedisManager(
            RedisConnection("unused", 1),
            ServerIdentity { "test" },
            poolFactory = { mockk<JedisPooled>(relaxed = true) },
        )
        ProxyRedisHealthBinding.install(replacement, null)
        old.onOperation(RedisOperation.PUBLISH, RedisOperationResult.SUCCESS, 0)
        ProxyRedisHealthBinding.dependencies(replacement)["redis_publish_fresh"] shouldBe false
        ProxyRedisHealthBinding.clear()
        ProxyRedisHealthBinding.dependencies(manager)["redis"] shouldBe false
        manager.close()
        replacement.close()
    }

    "Discord read success does not mask failed writes" {
        val health = DiscordTransportHealth { 1L }
        health.record(write = true, success = false)
        health.record(write = false, success = true)
        health.dependencies(true, true)["discord_rest"] shouldBe false
        health.record(write = true, success = true)
        health.dependencies(true, true).values.all { it } shouldBe true
        health.dependencies(true, false)["discord_gateway"] shouldBe false
    }

    "Discord body read timeout is observed even when JDA never emits its response event" {
        val health = DiscordTransportHealth { 1L }
        health.record(write = true, success = true)
        val call = OkHttpClient().newCall(Request.Builder().url("https://discord.invalid/").post("".toRequestBody()).build())
        health.httpListener.callFailed(call, SocketTimeoutException("Read timed out"))
        health.record(write = false, success = true)
        health.dependencies(true, true)["discord_rest"] shouldBe false
        health.record(write = true, success = true)
        health.dependencies(true, true)["discord_rest"] shouldBe true
    }

    "Discord disabled is neutral but unobserved or stale REST is not healthy" {
        var now = 0L
        val health = DiscordTransportHealth { now }
        health.dependencies(false, false) shouldBe emptyMap()
        health.dependencies(true, true)["discord_rest_fresh"] shouldBe false
        health.record(write = false, success = true)
        health.dependencies(true, true).values.all { it } shouldBe true
        now = 91_000_000_000L
        health.dependencies(true, true)["discord_rest_fresh"] shouldBe false
    }
})
