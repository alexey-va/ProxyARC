package ru.arc.ai

data class NormalizedChatReply(
    val parts: List<String> = emptyList(),
    val skipReason: String? = null,
) {
    val hasText: Boolean get() = parts.isNotEmpty()
    val text: String? get() = parts.firstOrNull()
}
