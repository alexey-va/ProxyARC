package ru.arc.ai.memory

import com.openai.models.chat.completions.ChatCompletionMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import java.util.Deque

/**
 * Trims and compacts assistant dialog history when it grows too large.
 */
object AssistantHistoryCompactor {

    fun compactObservations(
        observations: Deque<String>,
        maxLines: Int,
        summaryPrefix: String = "[сводка чата: ",
    ) {
        if (observations.size <= maxLines) return
        val overflow = observations.size - maxLines
        val dropped = ArrayList<String>(overflow)
        repeat(overflow) {
            observations.pollFirst()?.let { dropped.add(it) }
        }
        if (dropped.isNotEmpty()) {
            val summary = summaryPrefix + dropped.joinToString(" | ") + "]"
            observations.addFirst(summary)
        }
    }

    fun compactHistory(
        history: Deque<ChatCompletionMessageParam>,
        labels: Deque<String>,
        threshold: Int,
        keepRecent: Int,
        summaryAuthor: String = "history",
    ) {
        if (history.size <= threshold || labels.size != history.size) return
        val removeCount = (history.size - keepRecent).coerceAtLeast(0)
        if (removeCount <= 0) return

        val dropped = ArrayList<String>(removeCount)
        repeat(removeCount) {
            history.pollFirst()
            labels.pollFirst()?.let { dropped.add(it) }
        }
        if (dropped.isEmpty()) return

        val summaryText = "[сводка старого диалога: ${dropped.joinToString(" | ")}]"
        val summary =
            ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder()
                    .content(summaryText)
                    .name(summaryAuthor)
                    .build(),
            )
        history.addFirst(summary)
        labels.addFirst(summaryText)
    }
}
