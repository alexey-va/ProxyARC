package ru.arc.ai.routing.pipeline

import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.router.RouteDecision

data class PipelineContext(
    val message: InboundMessage,
    val meta: InboundMeta,
    val decision: RouteDecision? = null,
)
