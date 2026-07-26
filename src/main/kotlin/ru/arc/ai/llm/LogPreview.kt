package ru.arc.ai.llm

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Bounded, single-line representation for untrusted player/model text in logs. */
object LogPreview {
    const val DEFAULT_MAX_CHARS = 320

    fun of(
        value: String?,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String {
        if (value == null) return "<null>"
        val normalized =
            value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\\n")
        val limit = maxChars.coerceAtLeast(32)
        if (normalized.length <= limit) return normalized
        return normalized.take(limit) +
            "…[len=${normalized.length} sha256=${sha256(value).take(12)}]"
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
