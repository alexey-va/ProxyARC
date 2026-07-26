package ru.arc.xserver

import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import java.util.concurrent.ThreadLocalRandom

class JoinMessages(
    @JvmField var player: String,
) : Entity,
    Mergeable<JoinMessages> {
    @Volatile
    @JvmField
    var joinMessages: Set<String> = emptySet()

    @Volatile
    @JvmField
    var leaveMessages: Set<String> = emptySet()

    @JvmField
    var timestamp: Long = System.currentTimeMillis()

    init {
        timestamp = System.currentTimeMillis()
    }

    fun randomJoinMessage(): String? = randomFrom(joinMessages)

    fun randomLeaveMessage(): String? = randomFrom(leaveMessages)

    override fun id(): String = player

    @Synchronized
    override fun merge(other: JoinMessages) {
        joinMessages = other.joinMessages.toSet()
        leaveMessages = other.leaveMessages.toSet()
        timestamp = other.timestamp
    }

    private fun randomFrom(messages: Set<String>): String? {
        val snapshot = messages
        if (snapshot.isEmpty()) return null
        return snapshot.elementAt(ThreadLocalRandom.current().nextInt(snapshot.size))
    }
}
