package ru.arc.xserver

import org.apache.logging.log4j.LogManager
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.JedisPubSub
import ru.arc.CommonCore
import ru.arc.xserver.repos.ProxyScheduler
import ru.arc.xserver.repos.RedisRepoMessager
import java.util.Arrays
import java.util.HashSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class RedisManager(
    ip: String,
    port: Int,
    userName: String,
    password: String,
) : JedisPubSub() {
    private var sub: JedisPooled? = null
    private var pub: JedisPooled? = null
    private var executorService: ExecutorService? = null

    private val channelListeners: MutableMap<String, MutableList<ChannelListener>> = ConcurrentHashMap()
    private val channelList: MutableSet<String> = HashSet()
    private var running: Future<*>? = null

    init {
        connect(ip, port, userName, password)
    }

    fun connect(ip: String, port: Int, userName: String, password: String) {
        close()
        sub = JedisPooled(ip, port, userName, password)
        pub = JedisPooled(ip, port, userName, password)
        executorService = Executors.newCachedThreadPool()
        init()
    }

    override fun onPong(message: String) {
        println("method: onPong message: $message")
    }

    override fun onMessage(channel: String, message: String) {
        try {
            if (!channelListeners.containsKey(channel)) {
                println("No listener for $channel")
                return
            }

            val strings = message.split(SERVER_DELIMITER, limit = 2)
            channelListeners[channel]?.forEach { listener ->
                listener.consume(channel, strings[1], strings[0])
            }
        } catch (e: Exception) {
            log.error("Error processing message", e)
        }
    }

    fun registerChannelUnique(channel: String, channelListener: ChannelListener) {
        channelListeners.putIfAbsent(channel, ArrayList())
        channelListeners[channel]?.clear()
        channelListeners[channel]?.add(channelListener)
        channelList.add(channel)
    }

    fun init() {
        if (running != null && running?.isCancelled == false) {
            log.debug("Stopping old subscription")
            running?.cancel(true)
        }
        log.info("Starting new subscription")
        running = executorService?.submit {
            log.info("Subscribing to: {}", channelList)
            try {
                sub?.subscribe(this, *channelList.toTypedArray())
            } catch (e: Exception) {
                object : ProxyScheduler() {
                    override fun run() {
                        init()
                    }
                }.runTaskLater(20)
            }
        }
    }

    override fun onSubscribe(channel: String, subscribedChannels: Int) {
        println("$channel subbed!")
    }

    fun publish(channel: String, message: String) {
        executorService?.execute {
            pub?.publish(channel, CommonCore.serverName + SERVER_DELIMITER + message)
        }
    }

    fun saveMap(key: String, map: Map<String, String>) {
        executorService?.execute {
            pub?.hmset(key, map)
        }
    }

    fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> {
        if (keyValuePairs.isEmpty()) {
            log.debug("No key value pairs to save for key {}", key)
        }
        log.debug("Saving map key: {} \n {}", key, Arrays.toString(keyValuePairs))
        return try {
            val pairs = keyValuePairs.toList().chunked(2).map { chunk ->
                chunk[0] to chunk.getOrNull(1)
            }

            val delete = pairs.filter { (_, value) -> value == null }.map { (key, _) -> key }.toTypedArray()
            val update = pairs.filter { (_, value) -> value != null }.associate { (key, value) -> key to value!! }
            log.debug("Saving map key: {} \n {}", key, update)
            CompletableFuture.supplyAsync(
                {
                    try {
                        if (delete.isNotEmpty()) pub?.hdel(key, *delete)
                        if (update.isNotEmpty()) pub?.hmset(key, update)
                    } catch (e: Exception) {
                        log.error("Error saving map")
                    }
                    null
                },
                executorService,
            )
        } catch (e: Exception) {
            log.warn("Error saving map key: {}", key)
            CompletableFuture.completedFuture(null)
        }
    }

    fun loadMap(key: String): CompletableFuture<Map<String, String>> =
        CompletableFuture.supplyAsync({ pub?.hgetAll(key) ?: emptyMap() }, executorService)

    fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String>> =
        CompletableFuture.supplyAsync({ pub?.hmget(key, *mapKeys) ?: emptyList() }, executorService)

    fun close() {
        try {
            log.debug("Closing redis manager")
            if (running != null && running?.isCancelled == false) {
                running?.cancel(true)
            }
            sub?.close()
            pub?.close()
            executorService?.shutdown()
        } catch (e: Exception) {
            log.error("Error closing redis manager", e)
        }
    }

    fun unregisterChannel(updateChannel: String, messager: RedisRepoMessager) {
        channelListeners[updateChannel]?.remove(messager)
    }

    companion object {
        private val log = LogManager.getLogger(RedisManager::class.java)
        private const val SERVER_DELIMITER = "<>#<>#<>"
    }
}
