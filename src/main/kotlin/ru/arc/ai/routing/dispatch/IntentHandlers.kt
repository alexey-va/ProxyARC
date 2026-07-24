package ru.arc.ai.routing.dispatch

import ru.arc.ai.routing.dispatch.assistant.AssistantAgentDispatch
import ru.arc.ai.routing.dispatch.assistant.BugSurveyAgentDispatch
import ru.arc.ai.routing.dispatch.handlers.BugIntentHandler
import ru.arc.ai.routing.dispatch.handlers.ChatIntentHandler
import ru.arc.ai.routing.dispatch.handlers.SkipIntentHandler

/**
 * Registry factory — add new scenario handlers here.
 *
 * Agent-based scenario (most common):
 * ```
 * ModerationIntentHandler(agent),  // extends AssistantIntentHandler
 * ```
 *
 * Custom pipeline (no shared agent):
 * ```
 * CustomIntentHandler(),
 * ```
 */
object IntentHandlers {
    fun create(services: DispatchServices): IntentHandlerRegistry {
        val agent = AssistantAgentDispatch(services.proxyServer)
        val survey = BugSurveyAgentDispatch()
        return IntentHandlerRegistry(
            handlers =
                listOf(
                    SkipIntentHandler(),
                    ChatIntentHandler(agent),
                    BugIntentHandler(survey, agent),
                ),
        )
    }
}
