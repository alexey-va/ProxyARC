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
        val boundedMax = maxLines.coerceAtLeast(1)
        if (observations.size <= boundedMax) return
        val keepRecent = (boundedMax - 1).coerceAtLeast(0)
        val removeCount = observations.size - keepRecent
        var dropped = 0
        repeat(removeCount) {
            if (observations.pollFirst() != null) dropped++
        }
        observations.addFirst("$summaryPrefix$dropped старых строк опущено]")
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

        val samples =
            dropped
                .takeLast(4)
                .joinToString(" | ") {
                    it.replace('\n', ' ').trim().take(120)
                }
        val summaryText = "[сводка старого диалога: опущено ${dropped.size}; последние: $samples]"
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
