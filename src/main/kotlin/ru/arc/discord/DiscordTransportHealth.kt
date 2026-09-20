package ru.arc.discord

import net.dv8tion.jda.api.events.GenericEvent
import net.dv8tion.jda.api.events.http.HttpRequestEvent
import net.dv8tion.jda.api.hooks.EventListener
import net.dv8tion.jda.api.requests.Method
import okhttp3.Call
import java.io.IOException

/** Per-JDA cached REST evidence. A read-only probe never clears a failed mutation. */
internal class DiscordTransportHealth(private val clock: () -> Long = System::nanoTime) : EventListener {
    private var lastSuccess: Long? = null
    private var readFailed = false
    private var writeFailed = false

    // JDA may throw while parsing a response before it emits HttpRequestEvent.
    // OkHttp also reports failed response-body reads here, including socket timeouts.
    val httpListener = object : okhttp3.EventListener() {
        override fun callFailed(call: Call, ioe: IOException) {
            record(call.request().method !in setOf("GET", "HEAD"), success = false)
        }
    }

    override fun onEvent(event: GenericEvent) {
        if (event is HttpRequestEvent) {
            val response = event.response
            // Invalid user input and handled rate limits are not a dependency outage.
            if (response == null || response.isOk || response.isError || response.code >= 500 || response.code in setOf(401, 403)) {
                record(event.route.method !in setOf(Method.GET, Method.HEAD), response?.isOk == true)
            }
        }
    }

    @Synchronized
    fun record(write: Boolean, success: Boolean) {
        if (write) writeFailed = !success else readFailed = !success
        if (success) lastSuccess = clock()
    }

    @Synchronized
    fun dependencies(enabled: Boolean, connected: Boolean): Map<String, Boolean> {
        if (!enabled) return emptyMap()
        return mapOf(
            "discord_gateway" to connected,
            "discord_rest" to (!readFailed && !writeFailed),
            "discord_rest_fresh" to (lastSuccess?.let { clock() - it in 0..FRESHNESS_NANOS } == true),
        )
    }

    companion object {
        private const val FRESHNESS_NANOS = 90_000_000_000L
    }
}
