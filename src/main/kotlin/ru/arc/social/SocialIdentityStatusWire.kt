package ru.arc.social

import com.google.gson.Gson
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import java.util.UUID

/** Closed request/reply schema for the ArcRanks social-link status lookup. */
data class SocialIdentityStatusWire(
    val kind: String,
    val requestId: String,
    val replyTo: String? = null,
    val playerId: String,
    val discord: String? = null,
    val telegram: String? = null,
) {
    fun isRequest(): Boolean = replyTo == null && discord == null && telegram == null

    fun isReply(): Boolean = replyTo != null && discord in STATES && telegram in STATES

    fun validate() {
        require(kind == KIND) { "Unsupported social status message kind" }
        require(REQUEST_ID.matches(requestId)) { "Invalid social status request id" }
        require(runCatching { UUID.fromString(playerId) }.isSuccess) { "Invalid social status player id" }
        replyTo?.let { require(REQUEST_ID.matches(it)) { "Invalid social status reply id" } }
        require(isRequest() || isReply()) { "Invalid social status request/reply shape" }
    }

    companion object {
        const val CHANNEL = "arc.social_identity_status"
        const val KIND = "status"
        const val LINKED = "linked"
        const val UNLINKED = "unlinked"
        const val UNAVAILABLE = "unavailable"
        private val STATES = setOf(LINKED, UNLINKED, UNAVAILABLE)
        private val REQUEST_ID = Regex("[A-Za-z0-9:._-]{1,160}")
        private val CONTRACT = JsonObjectContract(
            allowedFields = setOf("kind", "requestId", "replyTo", "playerId", "discord", "telegram"),
            requiredFields = setOf("kind", "requestId", "playerId"),
        )
        private val BOUNDS = JsonResourceBounds(
            maxCharacters = 512,
            maxDepth = 4,
            maxContainerEntries = 8,
            maxTotalNodes = 16,
            maxStringCharacters = 160,
        )

        fun codec(gson: Gson = Gson()): BoundedJsonCodec<SocialIdentityStatusWire> =
            BoundedJsonCodec(gson, SocialIdentityStatusWire::class.java, CONTRACT, BOUNDS) { it.validate() }

        fun request(requestId: String, playerId: UUID): SocialIdentityStatusWire =
            SocialIdentityStatusWire(KIND, requestId, playerId = playerId.toString())

        fun reply(request: SocialIdentityStatusWire, discord: String, telegram: String): SocialIdentityStatusWire =
            SocialIdentityStatusWire(
                kind = KIND,
                requestId = request.requestId,
                replyTo = request.requestId,
                playerId = request.playerId,
                discord = discord,
                telegram = telegram,
            )
    }
}
