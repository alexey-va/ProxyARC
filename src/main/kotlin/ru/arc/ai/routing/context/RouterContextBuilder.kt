package ru.arc.ai.routing.context

import ru.arc.ai.routing.history.RouteHistoryStore
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.observe.ChatLog
import ru.arc.ai.routing.router.RouterConfig
import ru.arc.ai.tickets.IssueTicketStore

class RouterContextBuilder(
    private val chatLog: ChatLog,
    private val routeHistory: RouteHistoryStore,
    private val config: RouterConfig,
) {
    fun build(message: InboundMessage, meta: InboundMeta): RouterContext {
        val routerRecentOpen = config.recentOpenTickets
        return RouterContext(
            message = message,
            meta = meta,
            recentChat = chatLog.snapshot(config.maxContextLines),
            openTicket = IssueTicketStore.findOpenByReporter(message.player),
            recentOpenTickets =
                if (routerRecentOpen > 0) {
                    IssueTicketStore.listOpenRecent(routerRecentOpen)
                } else {
                    emptyList()
                },
            recentRoutes = routeHistory.snapshot(message.player, config.maxRouteHistory),
        )
    }
}
