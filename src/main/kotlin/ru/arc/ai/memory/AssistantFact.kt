package ru.arc.ai.memory

import java.time.Instant
import java.util.UUID

/**
 * Long-term fact stored for the chat assistant.
 */
data class AssistantFact(
    val id: String = UUID.randomUUID().toString(),
    val subject: String?,
    val fact: String,
    val confidence: Double,
    val rememberedAt: String = Instant.now().toString(),
    val source: String? = null,
)
