package ru.arc.staff

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.slf4j.LoggerFactory
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Sensitive delivery is separate from generic chat sync and verifies the current effective ACL each time. */
internal class StaffDiscordGateway(private val config: StaffBridgeConfig, private val jda: () -> JDA?) : AutoCloseable {
    private val queues = mutableMapOf<String, ArrayDeque<String>>()
    private val active = AtomicBoolean(true)
    private val sending = mutableSetOf<String>()
    private val lastPrivacyWarning = mutableMapOf<String, Long>()
    private var timer: ScheduledTask? = null

    fun start() { timer = Tasks.scheduler.runTimerAsync(40, 40, Runnable(::flush)) }

    fun channel(id: String, adminOnly: Boolean): TextChannel? {
        val api = jda()?.takeIf { it.status == JDA.Status.CONNECTED && CacheFlag.MEMBER_OVERRIDES in it.cacheFlags } ?: return null
        val target = api.getTextChannelById(id)?.takeIf { it.guild.id == config.guildId } ?: return null
        val allowedRoles = if (adminOnly) config.adminRoleIds else config.adminRoleIds + config.moderatorRoleIds
        if (runCatching { StaffDiscordPrivacy.privateChannel(target, allowedRoles) }.getOrDefault(false)) return target
        synchronized(lastPrivacyWarning) {
            val now = System.currentTimeMillis()
            if (now - (lastPrivacyWarning[id] ?: 0L) >= 60_000) {
                lastPrivacyWarning[id] = now
                log.warn("Staff Discord delivery blocked by destination privacy channel={}", id)
            }
        }
        return null
    }

    fun enqueue(channelId: String?, text: String) {
        if (channelId.isNullOrEmpty() || !active.get()) return
        synchronized(queues) {
            val queue = queues.getOrPut(channelId) { ArrayDeque() }
            // ponytail: bounded live tail; historical export belongs in the audit store, not a chat backlog.
            if (queue.size == 80) queue.removeFirst()
            queue.addLast(text.replace('\n', ' ').replace('\r', ' ').take(600))
        }
    }

    fun reply(channelId: String, text: String): CompletableFuture<Boolean> {
        val target = channel(channelId, adminOnly = channelId != config.commandChannelId)
            ?: return CompletableFuture.completedFuture(false)
        if (!active.get()) return CompletableFuture.completedFuture(false)
        return try {
            target.sendMessage(literal(text.take(1800))).setAllowedMentions(emptyList()).submit()
                .handle { _, failure -> failure == null }
        } catch (_: RuntimeException) { CompletableFuture.completedFuture(false) }
    }

    private fun flush() {
        if (!active.get()) return
        val ids = synchronized(queues) { queues.keys.toList() }
        ids.forEach { id ->
            val text = synchronized(queues) {
                if (id in sending) return@forEach
                val queue = queues[id] ?: return@forEach
                if (queue.isEmpty()) return@forEach
                val lines = mutableListOf<String>()
                var chars = 0
                while (queue.isNotEmpty() && lines.size < 8 && chars + queue.first().length < 1800) {
                    lines += queue.removeFirst()
                    chars += lines.last().length + 1
                }
                sending += id
                lines.joinToString("\n")
            }
            reply(id, text).whenComplete { _, _ -> synchronized(queues) { sending -= id } }
        }
    }

    override fun close() {
        active.set(false)
        timer?.cancel()
        synchronized(queues) { queues.clear(); sending.clear() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(StaffDiscordGateway::class.java)
        fun literal(text: String): String = "```\n${text.replace("`", "`\u200b").take(1992)}\n```"
    }
}

internal object StaffDiscordPrivacy {
    fun privateChannel(channel: TextChannel, allowedRoleIds: Set<String>): Boolean {
        val guild = channel.guild
        val everyone = channel.getPermissionOverride(guild.publicRole) ?: return false
        if (Permission.VIEW_CHANNEL !in everyone.denied || Permission.VIEW_CHANNEL in everyone.allowed) return false
        if (!guild.selfMember.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND)) return false
        return channel.permissionOverrides.all { override ->
            if (Permission.VIEW_CHANNEL !in override.allowed) true
            else if (override.isRoleOverride) {
                override.id in allowedRoleIds || override.role?.hasPermission(Permission.ADMINISTRATOR) == true
            } else {
                override.id == guild.selfMember.id || override.member?.isOwner == true ||
                    override.member?.hasPermission(Permission.ADMINISTRATOR) == true
            }
        }
    }
}
