package ru.arc.ai.routing.dispatch.handlers

import ru.arc.ai.AssistantRunMode
import ru.arc.ai.routing.dispatch.assistant.AssistantAgentDispatch
import ru.arc.ai.routing.router.RouteIntent

class ChatIntentHandler(
    agent: AssistantAgentDispatch,
) : AssistantIntentHandler(
    agent = agent,
    intent = RouteIntent.CHAT,
    mode = AssistantRunMode.CHAT,
    deliverPublicReply = true,
)
