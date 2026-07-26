package ru.arc.ai.routing.router

import org.slf4j.Logger
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.pipeline.PipelineContext
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

object RouteLog {
    fun describeError(error: Throwable): String {
        val root = unwrap(error)
        when (root) {
            is TimeoutException ->
                return "TimeoutException: request exceeded router timeout (see routing.timeout-sec)"
        }
        val name = root.javaClass.simpleName
        val detail = root.message?.trim()?.takeIf { it.isNotEmpty() }
        val cause =
            root.cause?.let { child ->
                if (child !== root) " cause=${describeError(child)}" else ""
            } ?: ""
        return if (detail != null) "$name: $detail$cause" else "$name (no message)$cause"
    }

    fun logClassified(
        log: Logger,
        message: InboundMessage,
        decision: RouteDecision,
        model: String?,
    ) {
        log.info(
            "Router classify {} «{}» → {} source={} conf={} parseOk={} model={} reason={}",
            message.player,
            snippet(message.displayText),
            decision.intent.wireName(),
            message.source.wireName(),
            decision.confidence,
            decision.parseOk,
            model ?: decision.model ?: "?",
            decision.reason,
        )
        if (!decision.parseOk && decision.raw.isNotBlank()) {
            log.warn(
                "Router raw response {}: {}",
                message.player,
                decision.raw.take(300),
            )
        }
    }

    fun logPolicyAdjust(
        log: Logger,
        message: InboundMessage,
        before: RouteDecision,
        after: RouteDecision,
    ) {
        if (before.intent == after.intent && before.reason == after.reason) return
        log.info(
            "Router policy {} «{}» {} → {} ({})",
            message.player,
            snippet(message.displayText),
            before.intent.wireName(),
            after.intent.wireName(),
            after.reason,
        )
    }

    fun logDispatch(
        log: Logger,
        context: PipelineContext,
        handlerName: String,
    ) {
        val decision = context.decision ?: return
        log.info(
            "Router dispatch {} «{}» intent={} handler={} conf={} reason={}",
            context.message.player,
            snippet(context.message.displayText),
            decision.intent.wireName(),
            handlerName,
            decision.confidence,
            decision.reason,
        )
    }

    fun logLlmError(
        log: Logger,
        player: String,
        model: String,
        error: Throwable,
    ) {
        log.warn(
            "Router LLM error player={} model={}: {}",
            player,
            model,
            describeError(error),
            unwrap(error),
        )
    }

    private fun snippet(text: String): String =
        text.replace("\n", " ").trim().take(120)

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (true) {
            val cause = current.cause
            if (cause == null) break
            if (current is CompletionException || current is ExecutionException) {
                current = cause
            } else {
                break
            }
        }
        return current
    }
}
