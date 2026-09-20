package ru.arc.ops

import ru.arc.redis.RedisManager
import ru.arc.redis.RedisOperation
import ru.arc.redis.RedisOperationResult
import ru.arc.redis.RedisReconnectPath
import ru.arc.redis.RedisReconnectResult
import ru.arc.redis.RedisTelemetrySink

/** Observes completed real operations; connection flags and PING cannot heal a failed write. */
internal class ProxyRedisHealth(
    private val clock: () -> Long = System::nanoTime,
) : RedisTelemetrySink {
    private val failures = mutableSetOf<RedisOperation>()
    private var lastPublish: Long? = null

    @Synchronized
    override fun onOperation(operation: RedisOperation, result: RedisOperationResult, durationNanos: Long) {
        if (result == RedisOperationResult.SUCCESS) {
            failures.remove(operation)
            if (operation == RedisOperation.PUBLISH) lastPublish = clock()
        } else {
            failures.add(operation)
        }
    }

    override fun onReconnect(path: RedisReconnectPath, result: RedisReconnectResult) = Unit

    @Synchronized
    fun dependencies(connected: Boolean, subscribed: Boolean): Map<String, Boolean> = mapOf(
        "redis" to connected,
        "redis_subscription" to subscribed,
        "redis_operations" to failures.isEmpty(),
        "redis_publish_fresh" to (lastPublish?.let { clock() - it in 0..FRESHNESS_NANOS } == true),
    )

    companion object {
        // ProxyTasks publishes presence every second; tolerate brief scheduler delays, never indefinite silence.
        private const val FRESHNESS_NANOS = 45_000_000_000L
    }
}

/** One lifecycle-owned fanout keeps health independent of the optional Prometheus exporter. */
internal object ProxyRedisHealthBinding {
    @Volatile private var current: Pair<RedisManager, ProxyRedisHealth>? = null

    fun install(manager: RedisManager, metrics: RedisTelemetrySink?): RedisTelemetrySink {
        val health = current?.takeIf { it.first === manager }?.second ?: ProxyRedisHealth()
        current = manager to health
        val sink = object : RedisTelemetrySink {
            override fun onOperation(operation: RedisOperation, result: RedisOperationResult, durationNanos: Long) {
                health.onOperation(operation, result, durationNanos)
                metrics?.onOperation(operation, result, durationNanos)
            }

            override fun onReconnect(path: RedisReconnectPath, result: RedisReconnectResult) {
                metrics?.onReconnect(path, result)
            }
        }
        manager.installTelemetry(sink)
        return sink
    }

    fun clear() { current = null }

    fun dependencies(manager: RedisManager?): Map<String, Boolean> {
        val binding = current
        return if (manager != null && binding?.first === manager) {
            binding.second.dependencies(manager.isConnected(), manager.isSubscriptionActive())
        } else {
            mapOf("redis" to false)
        }
    }
}
