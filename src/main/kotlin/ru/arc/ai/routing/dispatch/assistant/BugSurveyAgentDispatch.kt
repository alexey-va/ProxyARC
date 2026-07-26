package ru.arc.ai.routing.dispatch.assistant

import ru.arc.ai.AssistantRunMode
import ru.arc.ai.routing.pipeline.PipelineContext
import ru.arc.ai.routing.survey.BugSurveySession
import ru.arc.velocity.Velocity

/** Runs the dedicated bug-survey Скорен agent (isolated history from chat). */
class BugSurveyAgentDispatch {
    fun enqueue(
        context: PipelineContext,
        investigation: BugSurveySession? = null,
    ) {
        val assistant = Velocity.bugSurveyAssistant ?: return
        val player = context.message.player
        val message = context.message.displayText

        if (investigation != null && !investigation.isPrimary(player)) {
            assistant.addChatMessage(
                "scope: игрок $player отвечает по расследованию " +
                    "${investigation.ticketId ?: "без тикета"} " +
                    "(репортёр ${investigation.player}, участники: ${investigation.participants.joinToString()}). " +
                    "updateissueticket appendDescription с его словами; PM ему если нужно уточнить.",
                "bug-scope",
            )
        }

        assistant.tryEnqueue(
            triggerPlayer = player,
            triggerMessage = message,
            mode = AssistantRunMode.BUG_SURVEY,
            triggerServer = context.message.server,
            source = context.message.source.wireName(),
        )
    }
}
