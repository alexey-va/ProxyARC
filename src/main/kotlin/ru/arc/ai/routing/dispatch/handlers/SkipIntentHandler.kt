package ru.arc.ai.routing.dispatch.handlers

import org.slf4j.LoggerFactory
import ru.arc.ai.PlayerMessaging
import ru.arc.ai.routing.RoutingModule
import ru.arc.ai.routing.dispatch.DispatchServices
import ru.arc.ai.routing.dispatch.IntentHandler
import ru.arc.ai.routing.dispatch.RouteDedup
import ru.arc.ai.routing.pipeline.PipelineContext
import ru.arc.ai.routing.router.RouteIntent
import ru.arc.ai.routing.router.RouteLog
import ru.arc.ai.routing.router.RouterBugHeuristic
import ru.arc.ai.routing.survey.BugSurveySessionStore
import ru.arc.ai.tickets.IssueTicketStore

class SkipIntentHandler : IntentHandler {
    override val intent = RouteIntent.SKIP

    private val log = LoggerFactory.getLogger(SkipIntentHandler::class.java)

    override fun dispatch(
        context: PipelineContext,
        services: DispatchServices,
    ) {
        val decision = context.decision ?: return
        if (services.routerConfig.logRouteInfo) {
            RouteLog.logDispatch(log, context, "skip")
        } else if (services.routerConfig.logSkipAtDebug) {
            log.debug(
                "Route skip {} «{}» reason={} conf={}",
                context.message.player,
                context.message.displayText,
                decision.reason,
                decision.confidence,
            )
        }
        maybeSendSkipPrivateMessage(context, services)
    }

    private fun maybeSendSkipPrivateMessage(
        context: PipelineContext,
        services: DispatchServices,
    ) {
        if (!services.assistantConfig.bool("bug.skip-notify-pm.enabled", false)) return

        val message = context.message
        if (message.allowsChatRouting(context.meta)) return

        val player = message.player
        val text = message.displayText
        if (
            RouterBugHeuristic.looksLikeOptOut(text) ||
            RouterBugHeuristic.looksLikeTrollNoise(text)
        ) {
            return
        }
        val survey = BugSurveySessionStore.findForPlayer(player)
        val openTicket = IssueTicketStore.findOpenByReporter(player)
        val bugAttempt =
            RouterBugHeuristic.looksLikeBugReport(text) ||
                survey != null ||
                openTicket != null ||
                context.meta.replyToBot ||
                context.meta.continuationWithBot

        if (!bugAttempt) return

        val dedupKey = "skip-pm:${player.lowercase()}:${text.lowercase().hashCode()}"
        if (RouteDedup.isDuplicate(dedupKey)) {
            log.debug("Skip PM dedup {} «{}»", player, text)
            return
        }

        val pmText =
            when {
                RouterBugHeuristic.looksLikeJoke(text) && !RouterBugHeuristic.looksLikeUiBug(text) ->
                    "похоже на прикол, не баг — если реально что-то сломалось, напиши что именно"
                RouterBugHeuristic.looksLikeBugReport(text) ||
                    RouterBugHeuristic.looksLikeVagueBugClaim(text) ->
                    "есть намёк на баг — напиши команду и мир, отвечу в личку"
                survey != null || openTicket != null ->
                    "не похоже на баг — если что-то сломалось, напиши что именно (команда, мир)"
                else ->
                    "не баг — если что-то не работает, напиши команду и сервер, отвечу в личку"
            }

        val result = PlayerMessaging.sendPrivate(player, pmText)
        when (result["status"]) {
            "sent" -> {
                RoutingModule.recordBotReply(player)
                log.info("Skip PM sent to {} on «{}»", player, text.take(80))
            }
            "offline" ->
                log.info(
                    "Skip PM logged for offline {} on «{}»: {}",
                    player,
                    text.take(80),
                    pmText.take(80),
                )
            else -> log.debug("Skip PM not sent to {}: {}", player, result)
        }
    }
}
