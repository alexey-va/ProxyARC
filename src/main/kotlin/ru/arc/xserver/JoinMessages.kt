package ru.arc.xserver

import ru.arc.join.JoinAnnouncementKind
import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import java.util.concurrent.ThreadLocalRandom

class JoinMessages(
    @JvmField var player: String,
) : Entity,
    Mergeable<JoinMessages> {
    constructor() : this("")

    @Volatile
    @JvmField
    var joinMessages: Set<String> = emptySet()

    @Volatile
    @JvmField
    var leaveMessages: Set<String> = emptySet()

    @Volatile
    @JvmField
    var customJoinMessages: Set<String> = emptySet()

    @Volatile
    @JvmField
    var customLeaveMessages: Set<String> = emptySet()

    @JvmField
    var timestamp: Long = System.currentTimeMillis()

    init {
        timestamp = System.currentTimeMillis()
    }

    fun randomJoinMessage(): String? = randomFrom(joinMessages)

    fun randomLeaveMessage(): String? = randomFrom(leaveMessages)

    fun randomMessage(kind: JoinAnnouncementKind): String? =
        when (kind) {
            JoinAnnouncementKind.FIRST_TIME -> null
            JoinAnnouncementKind.JOIN -> randomFrom(joinMessages)
            JoinAnnouncementKind.LEAVE -> randomFrom(leaveMessages)
        }

    override fun id(): String = player

    @Synchronized
    override fun merge(other: JoinMessages) {
        joinMessages = other.joinMessages.orEmpty().toSet()
        leaveMessages = other.leaveMessages.orEmpty().toSet()
        customJoinMessages = other.customJoinMessages.orEmpty().toSet()
        customLeaveMessages = other.customLeaveMessages.orEmpty().toSet()
        timestamp = other.timestamp
    }

    companion object {
        const val CUSTOM_MESSAGE_PREFIX = "%player_name% "
        const val CUSTOM_TEMPLATE_PREFIX = CustomJoinMessageTemplate.FULL_PREFIX
        const val MAX_CUSTOM_MESSAGES_PER_KIND = 10
        const val MAX_CUSTOM_MESSAGE_LENGTH = 120
        const val MAX_CUSTOM_TEMPLATE_LENGTH = CustomJoinMessageTemplate.MAX_LENGTH
        const val MAX_CUSTOM_VISIBLE_LENGTH = CustomJoinMessageTemplate.MAX_VISIBLE_LENGTH

        fun validCustomMessages(messages: Set<String>): List<String> =
            messages.asSequence()
                .filter(CustomJoinMessageTemplate::valid)
                .map(String::trim)
                .distinct()
                .take(MAX_CUSTOM_MESSAGES_PER_KIND)
                .toList()

        fun customSelectionKey(message: String): String = CustomJoinMessageTemplate.selectionKey(message.trim())
    }

    private fun randomFrom(messages: Set<String>): String? {
        val snapshot = messages.filter(String::isNotBlank)
        if (snapshot.isEmpty()) return null
        return snapshot[ThreadLocalRandom.current().nextInt(snapshot.size)]
    }
}
