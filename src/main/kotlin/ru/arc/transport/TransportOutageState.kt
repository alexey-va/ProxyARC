package ru.arc.transport

/** Tracks one outage transition and counts repeats until the next successful operation. */
internal class TransportOutageState {
    private var failures = 0

    @Synchronized
    fun failed(): Boolean {
        if (failures < Int.MAX_VALUE) failures++
        return failures == 1
    }

    /** Returns the number of repeats after the first failure, or null when no outage was active. */
    @Synchronized
    fun recovered(): Int? {
        if (failures == 0) return null
        return (failures - 1).also { failures = 0 }
    }

    @Synchronized
    fun reset() {
        failures = 0
    }
}
