package ru.arc.xserver

import ru.arc.xserver.repos.RepoData

class JoinMessages(
    @JvmField var player: String,
) : RepoData<JoinMessages>() {
    @JvmField
    var joinMessages: MutableSet<String> = HashSet()

    @JvmField
    var leaveMessages: MutableSet<String> = HashSet()

    @JvmField
    var timestamp: Long = System.currentTimeMillis()

    init {
        timestamp = System.currentTimeMillis()
    }

    fun addJoinMessage(message: String) {
        joinMessages.add(message)
        dirty = true
    }

    fun removeJoinMessage(message: String) {
        joinMessages.remove(message)
        dirty = true
    }

    fun addLeaveMessage(message: String) {
        leaveMessages.add(message)
        dirty = true
    }

    fun removeLeaveMessage(message: String) {
        leaveMessages.remove(message)
        dirty = true
    }

    override fun id(): String = player

    override fun isRemove(): Boolean =
        System.currentTimeMillis() - timestamp > 1000 * 60 * 60 * 24 * 7 &&
            joinMessages.isEmpty() &&
            leaveMessages.isEmpty()

    override fun merge(other: JoinMessages) {
        joinMessages.clear()
        joinMessages.addAll(other.joinMessages)
        leaveMessages.clear()
        leaveMessages.addAll(other.leaveMessages)
    }
}
