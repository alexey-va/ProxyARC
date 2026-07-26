package ru.arc.discord

import net.dv8tion.jda.api.entities.Message
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal class DiscordChatCleaner(
    private val executor: Executor,
    private val historyProvider: (String) -> Iterable<Message>?,
    private val deleteMessage: (Message) -> Unit = { message ->
        message.delete().queue()
    },
    private val deleteDelayMs: Long = DEFAULT_DELETE_DELAY_MS,
    private val pause: (Long) -> Unit = Thread::sleep,
) : AutoCloseable {
    private val activeTasks = ConcurrentHashMap<String, AtomicBoolean>()
    private val closed = AtomicBoolean(false)

    init {
        require(deleteDelayMs >= 0) { "deleteDelayMs must not be negative" }
    }

    fun start(channelId: String) {
        if (closed.get()) return
        val active = AtomicBoolean(true)
        activeTasks.put(channelId, active)?.set(false)
        try {
            executor.execute {
                clean(channelId, active)
            }
        } catch (error: RejectedExecutionException) {
            activeTasks.remove(channelId, active)
            log.debug("Discord chat cleanup rejected for channel {}", channelId, error)
        }
    }

    fun stop(channelId: String) {
        activeTasks.remove(channelId)?.set(false)
    }

    private fun clean(
        channelId: String,
        active: AtomicBoolean,
    ) {
        try {
            if (!active.get() || closed.get()) return
            val history = historyProvider(channelId) ?: return
            var index = 0
            for (message in history) {
                if (!active.get() || closed.get()) return
                if (index++ < KEEP_RECENT_MESSAGES) continue
                deleteMessage(message)
                if (deleteDelayMs > 0) {
                    pause(deleteDelayMs)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            log.warn("Discord chat cleanup failed for channel {}", channelId, error)
        } finally {
            activeTasks.remove(channelId, active)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeTasks.values.forEach { it.set(false) }
        activeTasks.clear()
    }

    internal fun activeTaskCount(): Int = activeTasks.size

    companion object {
        private val log = LoggerFactory.getLogger(DiscordChatCleaner::class.java)
        private const val KEEP_RECENT_MESSAGES = 5
        private const val DEFAULT_DELETE_DELAY_MS = 5_000L
    }
}
