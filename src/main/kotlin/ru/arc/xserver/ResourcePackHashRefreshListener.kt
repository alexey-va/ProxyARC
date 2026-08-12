package ru.arc.xserver

import ru.arc.redis.ChannelListener
import ru.arc.redis.resourcepack.ResourcePackPublication
import java.util.concurrent.CompletableFuture

internal class ResourcePackHashRefreshListener(
    private val commandAvailable: () -> Boolean,
    private val executeGenerateHashes: () -> CompletableFuture<Boolean>,
    private val acknowledge: (String, ResourcePackPublication.Request) -> CompletableFuture<*>,
    private val info: (String) -> Unit = {},
    private val warn: (String) -> Unit = {},
    private val error: (String, Throwable?) -> Unit = { _, _ -> },
) : ChannelListener {
    private data class PendingRequest(
        val originServer: String,
        val request: ResourcePackPublication.Request,
    )

    private data class Batch(
        val sha256: String,
        val requests: MutableList<PendingRequest>,
    )

    private val lock = Any()
    private var active: Batch? = null
    private val pending = linkedMapOf<String, MutableList<PendingRequest>>()
    private var lastSuccessfulSha256: String? = null

    override fun consume(
        channel: String,
        message: String,
        originServer: String,
    ) {
        if (channel != ResourcePackPublication.CHANNEL) {
            warn("Ignored resource-pack publication on unexpected channel '$channel'")
            return
        }
        if (originServer !in PAPER_SERVER_IDENTITIES) {
            warn("Ignored resource-pack publication from untrusted server '$originServer'")
            return
        }
        val request = ResourcePackPublication.decode(message)
        if (request == null) {
            warn("Ignored malformed resource-pack publication from '$originServer'")
            return
        }
        val pendingRequest = PendingRequest(originServer, request)

        var start: Batch? = null
        var acknowledgeImmediately = false
        var rejection: String? = null
        synchronized(lock) {
            when {
                request.sha256 == lastSuccessfulSha256 -> acknowledgeImmediately = true
                active?.sha256 == request.sha256 -> {
                    val requests = checkNotNull(active).requests
                    if (requests.size >= MAX_REQUESTS_PER_HASH) {
                        rejection = "Too many resource-pack refresh requests for one hash"
                    } else {
                        requests.add(pendingRequest)
                    }
                }
                active == null -> {
                    Batch(request.sha256, mutableListOf(pendingRequest)).also {
                        active = it
                        start = it
                    }
                }
                else -> {
                    val requests = pending[request.sha256]
                    when {
                        requests != null && requests.size >= MAX_REQUESTS_PER_HASH ->
                            rejection = "Too many queued resource-pack refresh requests for one hash"
                        requests != null -> requests.add(pendingRequest)
                        pending.size >= MAX_PENDING_HASHES ->
                            rejection = "Resource-pack refresh queue is full"
                        else -> pending[request.sha256] = mutableListOf(pendingRequest)
                    }
                }
            }
        }

        when {
            rejection != null -> warn("$rejection; request from '$originServer' was rejected")
            acknowledgeImmediately -> acknowledgeRequest(pendingRequest)
            start != null -> startRefresh(checkNotNull(start))
            else -> info("Queued Velocity resource-pack hash refresh for ${request.sha256.take(12)}…")
        }
    }

    private fun startRefresh(batch: Batch) {
        if (!commandAvailable()) {
            finishRefresh(batch, false, IllegalStateException("VelocityResourcePacks command is unavailable"))
            return
        }

        info("Executing VelocityResourcePacks generatehashes for ${batch.sha256.take(12)}…")
        val future =
            try {
                executeGenerateHashes()
            } catch (failure: Throwable) {
                finishRefresh(batch, false, failure)
                return
            }
        future.whenComplete { accepted, failure ->
            finishRefresh(batch, failure == null && accepted == true, failure)
        }
    }

    private fun finishRefresh(
        batch: Batch,
        succeeded: Boolean,
        failure: Throwable?,
    ) {
        val completedRequests: List<PendingRequest>
        val next: Batch?
        synchronized(lock) {
            if (active !== batch) return
            completedRequests = batch.requests.toList()
            if (succeeded) lastSuccessfulSha256 = batch.sha256
            active = null

            val iterator = pending.entries.iterator()
            next =
                if (iterator.hasNext()) {
                    val entry = iterator.next()
                    iterator.remove()
                    Batch(entry.key, entry.value).also { active = it }
                } else {
                    null
                }
        }

        if (succeeded) {
            info("VelocityResourcePacks accepted generatehashes for ${batch.sha256.take(12)}…")
            completedRequests.forEach(::acknowledgeRequest)
        } else {
            error("VelocityResourcePacks generatehashes failed for ${batch.sha256.take(12)}…", failure)
        }
        next?.let(::startRefresh)
    }

    private fun acknowledgeRequest(pendingRequest: PendingRequest) {
        val request = pendingRequest.request
        try {
            acknowledge(pendingRequest.originServer, request).whenComplete { _, failure ->
                if (failure != null) {
                    error("Unable to acknowledge resource-pack hash refresh ${request.requestId}", failure)
                }
            }
        } catch (failure: Throwable) {
            error("Unable to acknowledge resource-pack hash refresh ${request.requestId}", failure)
        }
    }

    companion object {
        private const val MAX_PENDING_HASHES = 8
        private const val MAX_REQUESTS_PER_HASH = 8
        private val PAPER_SERVER_IDENTITIES = setOf("spawn", "survival")
    }
}
