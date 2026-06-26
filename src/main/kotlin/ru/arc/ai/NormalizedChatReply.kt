package ru.arc.ai

data class NormalizedChatReply(
    val text: String? = null,
    val skipReason: String? = null,
) {
    val hasText: Boolean get() = !text.isNullOrBlank()
}
