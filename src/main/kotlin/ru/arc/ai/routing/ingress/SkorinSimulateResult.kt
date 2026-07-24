package ru.arc.ai.routing.ingress

data class SkorinSimulateResult(
    val player: String,
    val message: String,
    val intent: String,
    val reason: String,
    val confidence: Double,
    val parseOk: Boolean,
    val agentWait: String,
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "player" to player,
            "message" to message,
            "intent" to intent,
            "reason" to reason,
            "confidence" to confidence,
            "parseOk" to parseOk,
            "agentWait" to agentWait,
        )

    companion object {
        fun error(
            player: String,
            message: String,
            detail: String,
        ): SkorinSimulateResult =
            SkorinSimulateResult(
                player = player,
                message = message,
                intent = "error",
                reason = detail,
                confidence = 0.0,
                parseOk = false,
                agentWait = "skipped",
            )
    }
}
