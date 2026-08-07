package ru.arc.ops

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import ru.arc.ai.routing.survey.BugSurveySessionStore
import ru.arc.velocity.Velocity
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProxyOpsHttpServer(
    private val executorFactory: () -> ExecutorService = {
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "proxyarc-ops-http").apply { isDaemon = true }
        }
    },
    private val configProvider: () -> ProxyOpsHttpConfig = ProxyOpsHttpConfig::current,
    private val discordProvider: () -> DiscordOpsGateway? = { Velocity.discordBot },
) {
    private val log = LoggerFactory.getLogger(ProxyOpsHttpServer::class.java)
    private val mapper = ObjectMapper()
    private var httpServer: HttpServer? = null
    private var executor: ExecutorService? = null

    val actualPort: Int
        get() = httpServer?.address?.port ?: configProvider().bindPort

    fun start() {
        stop()
        val cfg = configProvider()
        if (!cfg.enabled) return

        val server = HttpServer.create(InetSocketAddress(cfg.bindHost, cfg.bindPort), 0)
        server.createContext("/ops") { exchange -> handle(exchange) }
        val newExecutor = executorFactory()
        server.executor = newExecutor
        try {
            server.start()
        } catch (e: Exception) {
            newExecutor.shutdownNow()
            server.stop(0)
            throw e
        }
        executor = newExecutor
        httpServer = server
        log.info("ProxyARC ops HTTP on {}:{}", cfg.bindHost, actualPort)
        if (cfg.token.isBlank() || cfg.token.startsWith("CHANGE_ME")) {
            log.warn("ProxyARC ops token not configured — set modules/ops-http.yml token")
        }
    }

    fun stop() {
        httpServer?.stop(0)
        httpServer = null
        executor?.shutdownNow()
        executor = null
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                respond(exchange, 204, "")
                return
            }
            val cfg = configProvider()
            val headers = exchange.requestHeaders.mapValues { it.value.firstOrNull().orEmpty() }
            if (!ProxyOpsAuth.isAuthorized(headers, cfg.token)) {
                respond(exchange, 401, ProxyOpsJson.error("Unauthorized"))
                return
            }
            route(exchange, cfg)
        } catch (t: Throwable) {
            log.error("ProxyARC ops handler failed", t)
            respond(exchange, 500, ProxyOpsJson.error(t.message ?: "Internal error"))
        } finally {
            exchange.close()
        }
    }

    private fun route(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        val path = exchange.requestURI.path.removePrefix("/ops").trim('/')
        val method = exchange.requestMethod.uppercase()

        when {
            path.isEmpty() && method == "GET" ->
                respond(exchange, 200, ProxyOpsJson.ok("routes" to routes(cfg)))
            path == "assistant/status" && method == "GET" ->
                respond(exchange, 200, statusJson())
            path == "assistant/simulate" && method == "POST" ->
                simulate(exchange, cfg, previewOnly = false)
            path == "assistant/preview" && method == "POST" ->
                simulate(exchange, cfg, previewOnly = true)
            path == "assistant/logs" && method == "GET" ->
                logs(exchange, cfg)
            path == "discord/guilds" && method == "GET" ->
                discordGuilds(exchange, cfg)
            path == "discord/channels" && method == "GET" ->
                discordChannels(exchange, cfg)
            path == "discord/roles" && method == "GET" ->
                discordRoles(exchange, cfg)
            path == "discord/member" && method == "GET" ->
                discordMember(exchange, cfg)
            path == "discord/messages" && method == "GET" ->
                discordHistory(exchange, cfg)
            path.startsWith("discord/messages/") && method == "GET" ->
                discordMessage(exchange, cfg, path.removePrefix("discord/messages/"))
            path == "discord/pins" && method == "GET" ->
                discordPins(exchange, cfg)
            path == "discord/search" && method == "GET" ->
                discordSearch(exchange, cfg)
            path == "discord/messages" && method == "POST" ->
                discordSend(exchange, cfg)
            path == "discord/messages/actions" && method == "POST" ->
                discordMessageMutation(exchange, cfg)
            path == "discord/threads/actions" && method == "POST" ->
                discordThreadMutation(exchange, cfg)
            path == "discord/channels/actions" && method == "POST" ->
                discordChannelMutation(exchange, cfg)
            path == "discord/roles/actions" && method == "POST" ->
                discordRoleMutation(exchange, cfg)
            path == "discord/members/actions" && method == "POST" ->
                discordMemberMutation(exchange, cfg)
            else -> respond(exchange, 404, ProxyOpsJson.error("Not found: /ops/$path"))
        }
    }

    private fun discordGuilds(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!requireDiscordRead(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        respond(exchange, 200, successJson(gateway.listGuilds(cfg.discordAllowedGuildIds)))
    }

    private fun discordChannels(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!requireDiscordRead(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        respond(
            exchange,
            200,
            successJson(gateway.listChannels(cfg.discordAllowedGuildIds, cfg.discordAllowedChannelIds)),
        )
    }

    private fun discordRoles(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!requireDiscordRead(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        val guildId = parseQuery(exchange.requestURI.rawQuery)["guildId"].orEmpty()
        if (!requireAllowedGuild(exchange, gateway, cfg, guildId)) return
        respond(exchange, 200, successJson(gateway.listRoles(guildId)))
    }

    private fun discordMember(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!requireDiscordRead(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        val query = parseQuery(exchange.requestURI.rawQuery)
        val guildId = query["guildId"].orEmpty()
        val userId = query["userId"].orEmpty()
        if (!requireAllowedGuild(exchange, gateway, cfg, guildId)) return
        if (!validSnowflake(userId)) {
            respond(exchange, 400, ProxyOpsJson.error("valid userId required"))
            return
        }
        val result =
            gateway.readMember(DiscordMemberReadRequest(guildId, userId))
                .get(DISCORD_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        respond(exchange, 200, successJson(result))
    }

    private fun discordHistory(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!requireDiscordRead(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        val query = parseQuery(exchange.requestURI.rawQuery)
        val channelId = query["channelId"].orEmpty()
        if (!validSnowflake(channelId)) {
            respond(exchange, 400, ProxyOpsJson.error("valid channelId required"))
            return
        }
        if (!gateway.isChannelAllowed(channelId, cfg.discordAllowedGuildIds, cfg.discordAllowedChannelIds)) {
            respond(exchange, 403, ProxyOpsJson.error("discord-channel-not-allowed"))
            return
        }
        val before = query["before"]?.takeIf(String::isNotBlank)
        if (before != null && !validSnowflake(before)) {
            respond(exchange, 400, ProxyOpsJson.error("before must be a valid message id"))
            return
        }
        val limit =
            query["limit"]?.toIntOrNull()
                ?.coerceIn(1, cfg.discordMaxHistory)
                ?: minOf(20, cfg.discordMaxHistory)
        val result =
            gateway.readHistory(
                DiscordHistoryRequest(
                    channelId = channelId,
                    limit = limit,
                    beforeMessageId = before,
                ),
            ).get(DISCORD_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        respond(exchange, 200, successJson(result))
    }

    private fun discordMessage(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
        messageId: String,
    ) {
        if (!requireDiscordRead(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        val query = parseQuery(exchange.requestURI.rawQuery)
        val channelId = query["channelId"].orEmpty()
        if (!validSnowflake(channelId) || !validSnowflake(messageId)) {
            respond(exchange, 400, ProxyOpsJson.error("valid channelId and messageId required"))
            return
        }
        if (!gateway.isChannelAllowed(channelId, cfg.discordAllowedGuildIds, cfg.discordAllowedChannelIds)) {
            respond(exchange, 403, ProxyOpsJson.error("discord-channel-not-allowed"))
            return
        }
        val result =
            gateway.readMessage(DiscordMessageRequest(channelId, messageId))
                .get(DISCORD_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        respond(exchange, 200, successJson(result))
    }

    private fun discordPins(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!requireDiscordRead(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        val query = parseQuery(exchange.requestURI.rawQuery)
        val channelId = query["channelId"].orEmpty()
        if (!validSnowflake(channelId)) {
            respond(exchange, 400, ProxyOpsJson.error("valid channelId required"))
            return
        }
        if (!gateway.isChannelAllowed(channelId, cfg.discordAllowedGuildIds, cfg.discordAllowedChannelIds)) {
            respond(exchange, 403, ProxyOpsJson.error("discord-channel-not-allowed"))
            return
        }
        val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 50
        val result =
            gateway.readPins(DiscordPinsRequest(channelId, limit))
                .get(DISCORD_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        respond(exchange, 200, successJson(result))
    }

    private fun discordSearch(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!requireDiscordRead(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        val query = parseQuery(exchange.requestURI.rawQuery)
        val guildId = query["guildId"].orEmpty()
        val channelId = query["channelId"]?.takeIf(String::isNotBlank)
        val authorId = query["authorId"]?.takeIf(String::isNotBlank)
        if (!requireAllowedGuild(exchange, gateway, cfg, guildId)) return
        if (channelId == null && "*" !in cfg.discordAllowedChannelIds) {
            respond(exchange, 400, ProxyOpsJson.error("channelId required for a bounded Discord search"))
            return
        }
        if (channelId != null &&
            (!validSnowflake(channelId) ||
                !gateway.isChannelAllowed(channelId, cfg.discordAllowedGuildIds, cfg.discordAllowedChannelIds))
        ) {
            respond(exchange, 403, ProxyOpsJson.error("discord-channel-not-allowed"))
            return
        }
        if (authorId != null && !validSnowflake(authorId)) {
            respond(exchange, 400, ProxyOpsJson.error("authorId must be a valid user id"))
            return
        }
        val text = query["query"].orEmpty()
        if (text.isBlank()) {
            respond(exchange, 400, ProxyOpsJson.error("query required"))
            return
        }
        val result =
            gateway.searchMessages(
                DiscordSearchRequest(
                    guildId = guildId,
                    query = text,
                    limit = query["limit"]?.toIntOrNull()?.coerceIn(1, 25) ?: 10,
                    channelId = channelId,
                    authorId = authorId,
                ),
            ).get(DISCORD_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        respond(exchange, 200, successJson(result))
    }

    private fun discordSend(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!cfg.discordWriteEnabled) {
            respond(exchange, 403, ProxyOpsJson.error("discord-write-disabled"))
            return
        }
        val gateway = requireDiscordGateway(exchange) ?: return
        val request = parseDiscordSendRequest(readBody(exchange))
        if (request == null) {
            respond(exchange, 400, ProxyOpsJson.error("Invalid JSON body"))
            return
        }
        if (!validSnowflake(request.channelId)) {
            respond(exchange, 400, ProxyOpsJson.error("valid channelId required"))
            return
        }
        if (!gateway.isChannelAllowed(request.channelId, cfg.discordAllowedGuildIds, cfg.discordWriteChannelIds)) {
            respond(exchange, 403, ProxyOpsJson.error("discord-channel-not-allowed"))
            return
        }
        if (request.content.isBlank() || request.content.length > DISCORD_MESSAGE_MAX_LENGTH) {
            respond(exchange, 400, ProxyOpsJson.error("content must contain 1..2000 characters"))
            return
        }
        if (request.replyToMessageId != null && !validSnowflake(request.replyToMessageId)) {
            respond(exchange, 400, ProxyOpsJson.error("replyToMessageId must be a valid message id"))
            return
        }
        if (request.confirmation != "SEND ${request.channelId}") {
            respond(exchange, 400, ProxyOpsJson.error("confirmation must equal SEND <channelId>"))
            return
        }
        val result =
            gateway.mutateMessage(
                DiscordMessageMutationRequest(
                    operation = DiscordMessageMutation.SEND,
                    channelId = request.channelId,
                    content = request.content,
                    replyToMessageId = request.replyToMessageId,
                ),
            ).get(DISCORD_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        log.info(
            "Discord ops sent message channel={} message={} reply={}",
            request.channelId,
            result["id"],
            request.replyToMessageId,
        )
        respond(exchange, 200, successJson(result))
    }

    private fun discordMessageMutation(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!requireDiscordWrite(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        val map = readJsonMap(exchange) ?: return
        val request = parseMessageMutation(map)
        if (request == null) {
            respond(exchange, 400, ProxyOpsJson.error("invalid Discord message action"))
            return
        }
        if (!gateway.isChannelAllowed(request.channelId, cfg.discordAllowedGuildIds, cfg.discordWriteChannelIds)) {
            respond(exchange, 403, ProxyOpsJson.error("discord-channel-not-allowed"))
            return
        }
        val target = request.messageId ?: request.channelId
        if (!requireConfirmation(exchange, map, "DISCORD MESSAGE ${request.operation.name} $target")) return
        val result = gateway.mutateMessage(request).get(DISCORD_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        logDiscordMutation("message", request.operation.name, target, result)
        respond(exchange, 200, successJson(result))
    }

    private fun discordThreadMutation(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!requireDiscordWrite(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        val map = readJsonMap(exchange) ?: return
        val request = parseThreadMutation(map)
        if (request == null) {
            respond(exchange, 400, ProxyOpsJson.error("invalid Discord thread action"))
            return
        }
        if (!gateway.isChannelAllowed(request.channelId, cfg.discordAllowedGuildIds, cfg.discordWriteChannelIds)) {
            respond(exchange, 403, ProxyOpsJson.error("discord-channel-not-allowed"))
            return
        }
        if (request.threadId != null &&
            !gateway.isChannelAllowed(request.threadId, cfg.discordAllowedGuildIds, cfg.discordWriteChannelIds)
        ) {
            respond(exchange, 403, ProxyOpsJson.error("discord-thread-not-allowed"))
            return
        }
        val target = request.threadId ?: request.channelId
        if (!requireConfirmation(exchange, map, "DISCORD THREAD ${request.operation.name} $target")) return
        val result = gateway.mutateThread(request).get(DISCORD_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        logDiscordMutation("thread", request.operation.name, target, result)
        respond(exchange, 200, successJson(result))
    }

    private fun discordChannelMutation(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!requireDiscordAdmin(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        val map = readJsonMap(exchange) ?: return
        val request = parseChannelMutation(map)
        if (request == null || !validSnowflake(request.guildId)) {
            respond(exchange, 400, ProxyOpsJson.error("invalid Discord channel action"))
            return
        }
        if (!requireAllowedGuild(exchange, gateway, cfg, request.guildId)) return
        val target = request.channelId ?: request.guildId
        if (!requireConfirmation(exchange, map, "DISCORD CHANNEL ${request.operation.name} $target")) return
        val result = gateway.mutateChannel(request).get(DISCORD_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        logDiscordMutation("channel", request.operation.name, target, result)
        respond(exchange, 200, successJson(result))
    }

    private fun discordRoleMutation(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!requireDiscordAdmin(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        val map = readJsonMap(exchange) ?: return
        val request = parseRoleMutation(map)
        if (request == null || !validSnowflake(request.guildId)) {
            respond(exchange, 400, ProxyOpsJson.error("invalid Discord role action"))
            return
        }
        if (!requireAllowedGuild(exchange, gateway, cfg, request.guildId)) return
        val target =
            if (request.operation in setOf(DiscordRoleMutation.ASSIGN, DiscordRoleMutation.REMOVE)) {
                "${request.roleId}:${request.userId}"
            } else {
                request.roleId ?: request.guildId
            }
        if (!requireConfirmation(exchange, map, "DISCORD ROLE ${request.operation.name} $target")) return
        val result = gateway.mutateRole(request).get(DISCORD_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        logDiscordMutation("role", request.operation.name, target, result)
        respond(exchange, 200, successJson(result))
    }

    private fun discordMemberMutation(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!requireDiscordAdmin(exchange, cfg)) return
        val gateway = requireDiscordGateway(exchange) ?: return
        val map = readJsonMap(exchange) ?: return
        val request = parseMemberMutation(map)
        if (request == null || !validSnowflake(request.guildId) || !validSnowflake(request.userId)) {
            respond(exchange, 400, ProxyOpsJson.error("invalid Discord member action"))
            return
        }
        if (!requireAllowedGuild(exchange, gateway, cfg, request.guildId)) return
        val target = "${request.guildId}:${request.userId}"
        if (!requireConfirmation(exchange, map, "DISCORD MEMBER ${request.operation.name} $target")) return
        val result = gateway.mutateMember(request).get(DISCORD_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        logDiscordMutation("member", request.operation.name, target, result)
        respond(exchange, 200, successJson(result))
    }

    private fun requireDiscordWrite(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ): Boolean {
        if (cfg.discordWriteEnabled) return true
        respond(exchange, 403, ProxyOpsJson.error("discord-write-disabled"))
        return false
    }

    private fun requireDiscordAdmin(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ): Boolean {
        if (cfg.discordAdminEnabled) return true
        respond(exchange, 403, ProxyOpsJson.error("discord-admin-disabled"))
        return false
    }

    private fun requireAllowedGuild(
        exchange: HttpExchange,
        gateway: DiscordOpsGateway,
        cfg: ProxyOpsHttpConfig,
        guildId: String,
    ): Boolean {
        if (!validSnowflake(guildId)) {
            respond(exchange, 400, ProxyOpsJson.error("valid guildId required"))
            return false
        }
        if (gateway.isGuildAllowed(guildId, cfg.discordAllowedGuildIds)) return true
        respond(exchange, 403, ProxyOpsJson.error("discord-guild-not-allowed"))
        return false
    }

    private fun requireConfirmation(
        exchange: HttpExchange,
        map: Map<String, Any?>,
        expected: String,
    ): Boolean {
        if (map["confirmation"]?.toString() == expected) return true
        respond(exchange, 400, ProxyOpsJson.error("confirmation must equal $expected"))
        return false
    }

    private fun logDiscordMutation(
        surface: String,
        operation: String,
        target: String,
        result: Map<String, Any?>,
    ) {
        log.info(
            "Discord ops mutation surface={} operation={} target={} resultId={}",
            surface,
            operation,
            target,
            result["id"] ?: result["channelId"] ?: result["roleId"] ?: result["userId"],
        )
    }

    private fun requireDiscordRead(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ): Boolean {
        if (cfg.discordReadEnabled) return true
        respond(exchange, 403, ProxyOpsJson.error("discord-read-disabled"))
        return false
    }

    private fun requireDiscordGateway(exchange: HttpExchange): DiscordOpsGateway? {
        val gateway = discordProvider()
        if (gateway?.isReady() == true) return gateway
        respond(exchange, 503, ProxyOpsJson.error("discord-not-ready"))
        return null
    }

    private fun successJson(payload: Map<String, Any?>): String =
        mapper.writeValueAsString(linkedMapOf<String, Any?>("ok" to true).apply { putAll(payload) })

    private fun simulate(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
        previewOnly: Boolean,
    ) {
        if (!cfg.simulateEnabled) {
            respond(exchange, 403, ProxyOpsJson.error("simulate-disabled"))
            return
        }
        val body = readBody(exchange)
        val req = parseSimulateRequest(body)
        if (req == null) {
            respond(exchange, 400, ProxyOpsJson.error("Invalid JSON body"))
            return
        }
        if (req.player.isBlank() || req.message.isBlank()) {
            respond(exchange, 400, ProxyOpsJson.error("player and message required"))
            return
        }
        val result =
            ru.arc.ai.routing.ingress.ChatIngress.simulateGameChat(
                player = req.player.trim(),
                message = req.message,
                rawText = req.rawText,
                server = req.server,
                replyToBot = req.replyToBot,
                continuationWithBot = req.continuationWithBot,
                waitSeconds = req.waitSeconds.coerceIn(5, 90),
                previewOnly = previewOnly,
            )
        respond(exchange, 200, mapper.writeValueAsString(result.toMap() + mapOf("ok" to true)))
    }

    private fun logs(
        exchange: HttpExchange,
        cfg: ProxyOpsHttpConfig,
    ) {
        if (!cfg.logsEnabled) {
            respond(exchange, 403, ProxyOpsJson.error("logs-disabled"))
            return
        }
        val query = parseQuery(exchange.requestURI.rawQuery)
        val pattern = query["pattern"] ?: "LLM|Router|Assistant|Route bug|Skip PM"
        val lines = query["lines"]?.toIntOrNull()?.coerceIn(1, 200) ?: 40
        val logPath = Velocity.dataFolder?.resolve("../../logs/latest.log")?.normalize()
        val text =
            if (logPath == null || !Files.exists(logPath)) {
                "(log file not found)"
            } else {
                grepLog(logPath, pattern, lines)
            }
        respond(
            exchange,
            200,
            ProxyOpsJson.ok(
                "pattern" to pattern,
                "lines" to lines,
                "text" to text,
            ),
        )
    }

    private fun grepLog(
        logPath: Path,
        pattern: String,
        maxLines: Int,
    ): String {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        return Files.lines(logPath).use { lines ->
            lines
                .filter { regex.containsMatchIn(it) }
                .toList()
                .takeLast(maxLines)
                .joinToString("\n")
                .ifBlank { "(no matches)" }
        }
    }

    private fun statusJson(): String {
        val surveyCount = BugSurveySessionStore.activeCount()
        return ProxyOpsJson.ok(
            "pipelineReady" to (ru.arc.ai.routing.RoutingModule.pipeline != null),
            "llmReady" to (Velocity.llmClient?.enabled == true),
            "activeSurveys" to surveyCount,
            "opsPort" to actualPort,
        )
    }

    private fun routes(cfg: ProxyOpsHttpConfig): List<String> =
        buildList {
            add("GET /ops/")
            add("GET /ops/assistant/status")
            if (cfg.simulateEnabled) add("POST /ops/assistant/simulate")
            if (cfg.simulateEnabled) add("POST /ops/assistant/preview")
            if (cfg.logsEnabled) add("GET /ops/assistant/logs?pattern=&lines=")
            if (cfg.discordReadEnabled) {
                add("GET /ops/discord/guilds")
                add("GET /ops/discord/channels")
                add("GET /ops/discord/roles?guildId=")
                add("GET /ops/discord/member?guildId=&userId=")
                add("GET /ops/discord/messages?channelId=&limit=&before=")
                add("GET /ops/discord/messages/{messageId}?channelId=")
                add("GET /ops/discord/pins?channelId=&limit=")
                add("GET /ops/discord/search?guildId=&query=&channelId=&authorId=&limit=")
            }
            if (cfg.discordWriteEnabled) {
                add("POST /ops/discord/messages")
                add("POST /ops/discord/messages/actions")
                add("POST /ops/discord/threads/actions")
            }
            if (cfg.discordAdminEnabled) {
                add("POST /ops/discord/channels/actions")
                add("POST /ops/discord/roles/actions")
                add("POST /ops/discord/members/actions")
            }
        }

    private fun readBody(exchange: HttpExchange): String {
        val bytes = exchange.requestBody.readNBytes(MAX_REQUEST_BODY_BYTES + 1)
        require(bytes.size <= MAX_REQUEST_BODY_BYTES) { "Request body too large" }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = URLDecoder.decode(part.substring(0, idx), StandardCharsets.UTF_8)
            val value = URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8)
            key to value
        }.toMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSimulateRequest(body: String): SimulateRequest? =
        try {
            val map = mapper.readValue(body, Map::class.java) as Map<String, Any?>
            SimulateRequest(
                player = map["player"]?.toString().orEmpty(),
                message = map["message"]?.toString().orEmpty(),
                rawText = map["rawText"]?.toString(),
                server = map["server"]?.toString() ?: "survival",
                replyToBot = map["replyToBot"] as? Boolean ?: false,
                continuationWithBot = map["continuationWithBot"] as? Boolean ?: false,
                waitSeconds = (map["waitSeconds"] as? Number)?.toInt() ?: 45,
            )
        } catch (_: Exception) {
            null
        }

    @Suppress("UNCHECKED_CAST")
    private fun parseDiscordSendRequest(body: String): DiscordSendHttpRequest? =
        try {
            val map = mapper.readValue(body, Map::class.java) as Map<String, Any?>
            DiscordSendHttpRequest(
                channelId = map["channelId"]?.toString()?.trim().orEmpty(),
                content = map["content"]?.toString().orEmpty(),
                replyToMessageId = map["replyToMessageId"]?.toString()?.trim()?.takeIf(String::isNotEmpty),
                confirmation = map["confirmation"]?.toString().orEmpty(),
            )
        } catch (_: Exception) {
            null
        }

    @Suppress("UNCHECKED_CAST")
    private fun readJsonMap(exchange: HttpExchange): Map<String, Any?>? =
        try {
            mapper.readValue(readBody(exchange), Map::class.java) as Map<String, Any?>
        } catch (_: Exception) {
            respond(exchange, 400, ProxyOpsJson.error("Invalid JSON body"))
            null
        }

    private fun parseMessageMutation(map: Map<String, Any?>): DiscordMessageMutationRequest? =
        try {
            val operation = enumValue<DiscordMessageMutation>(map.string("operation"))
            val channelId = map.string("channelId")
            val messageId = map.optionalString("messageId")
            val replyTo = map.optionalString("replyToMessageId")
            require(validSnowflake(channelId))
            require(messageId == null || validSnowflake(messageId))
            require(replyTo == null || validSnowflake(replyTo))
            val content = map.optionalString("content", preserveBlank = true)
            require(content == null || content.length <= DISCORD_MESSAGE_MAX_LENGTH)
            DiscordMessageMutationRequest(
                operation = operation,
                channelId = channelId,
                messageId = messageId,
                content = content,
                replyToMessageId = replyTo,
                embeds = if (map.containsKey("embeds")) map.mapList("embeds").map(::parseEmbed) else null,
                attachments = map.mapList("attachments").map(::parseAttachment),
                emoji = map.optionalString("emoji"),
                reason = map.optionalString("reason"),
            )
        } catch (_: Exception) {
            null
        }

    private fun parseThreadMutation(map: Map<String, Any?>): DiscordThreadMutationRequest? =
        try {
            val channelId = map.string("channelId")
            val threadId = map.optionalString("threadId")
            val starterMessageId = map.optionalString("starterMessageId")
            require(validSnowflake(channelId))
            require(threadId == null || validSnowflake(threadId))
            require(starterMessageId == null || validSnowflake(starterMessageId))
            DiscordThreadMutationRequest(
                operation = enumValue(map.string("operation")),
                channelId = channelId,
                threadId = threadId,
                name = map.optionalString("name", preserveBlank = true),
                starterMessageId = starterMessageId,
                content = map.optionalString("content", preserveBlank = true),
                embeds = map.mapList("embeds").map(::parseEmbed),
                attachments = map.mapList("attachments").map(::parseAttachment),
                archived = map.boolean("archived"),
                locked = map.boolean("locked"),
                pinned = map.boolean("pinned"),
                reason = map.optionalString("reason"),
            )
        } catch (_: Exception) {
            null
        }

    private fun parseChannelMutation(map: Map<String, Any?>): DiscordChannelMutationRequest? =
        try {
            DiscordChannelMutationRequest(
                operation = enumValue(map.string("operation")),
                guildId = map.string("guildId"),
                channelId = map.optionalString("channelId"),
                type = map.optionalString("type"),
                name = map.optionalString("name", preserveBlank = true),
                parentCategoryId = map.optionalString("parentCategoryId"),
                topic = map.optionalString("topic", preserveBlank = true),
                nsfw = map.boolean("nsfw"),
                slowmodeSeconds = map.integer("slowmodeSeconds"),
                bitrate = map.integer("bitrate"),
                userLimit = map.integer("userLimit"),
                position = map.integer("position"),
                permissionOverrides = map.mapList("permissionOverrides").map(::parsePermissionOverride),
                removePermissionOverrideIds = map.stringSet("removePermissionOverrideIds"),
                reason = map.optionalString("reason"),
            ).also { request ->
                require(request.channelId == null || validSnowflake(request.channelId))
                require(request.parentCategoryId == null || validSnowflake(request.parentCategoryId))
            }
        } catch (_: Exception) {
            null
        }

    private fun parseRoleMutation(map: Map<String, Any?>): DiscordRoleMutationRequest? =
        try {
            DiscordRoleMutationRequest(
                operation = enumValue(map.string("operation")),
                guildId = map.string("guildId"),
                roleId = map.optionalString("roleId"),
                userId = map.optionalString("userId"),
                name = map.optionalString("name", preserveBlank = true),
                color = map.optionalString("color"),
                permissions = if (map.containsKey("permissions")) map.stringSet("permissions") else null,
                hoisted = map.boolean("hoisted"),
                mentionable = map.boolean("mentionable"),
                reason = map.optionalString("reason"),
            ).also { request ->
                require(request.roleId == null || validSnowflake(request.roleId))
                require(request.userId == null || validSnowflake(request.userId))
            }
        } catch (_: Exception) {
            null
        }

    private fun parseMemberMutation(map: Map<String, Any?>): DiscordMemberMutationRequest? =
        try {
            DiscordMemberMutationRequest(
                operation = enumValue(map.string("operation")),
                guildId = map.string("guildId"),
                userId = map.string("userId"),
                nickname = map.optionalString("nickname", preserveBlank = true),
                durationSeconds = (map["durationSeconds"] as? Number)?.toLong(),
                enabled = map.boolean("enabled"),
                deleteMessageSeconds = map.integer("deleteMessageSeconds") ?: 0,
                reason = map.optionalString("reason"),
            )
        } catch (_: Exception) {
            null
        }

    private fun parseEmbed(map: Map<String, Any?>): DiscordEmbedSpec =
        DiscordEmbedSpec(
            title = map.optionalString("title", preserveBlank = true),
            description = map.optionalString("description", preserveBlank = true),
            url = map.optionalString("url"),
            color = map.optionalString("color"),
            timestamp = map.optionalString("timestamp"),
            authorName = map.optionalString("authorName", preserveBlank = true),
            authorUrl = map.optionalString("authorUrl"),
            authorIconUrl = map.optionalString("authorIconUrl"),
            footerText = map.optionalString("footerText", preserveBlank = true),
            footerIconUrl = map.optionalString("footerIconUrl"),
            thumbnailUrl = map.optionalString("thumbnailUrl"),
            imageUrl = map.optionalString("imageUrl"),
            fields =
                map.mapList("fields").map { field ->
                    DiscordEmbedFieldSpec(
                        name = field.string("name"),
                        value = field.string("value"),
                        inline = field.boolean("inline") ?: false,
                    )
                },
        )

    private fun parseAttachment(map: Map<String, Any?>): DiscordAttachmentSpec =
        DiscordAttachmentSpec(
            fileName = map.string("fileName"),
            dataBase64 = map.string("dataBase64"),
            description = map.optionalString("description", preserveBlank = true),
        )

    private fun parsePermissionOverride(map: Map<String, Any?>): DiscordPermissionOverrideSpec =
        DiscordPermissionOverrideSpec(
            targetType = map.string("targetType"),
            targetId = map.string("targetId"),
            allow = map.stringSet("allow"),
            deny = map.stringSet("deny"),
        ).also { require(validSnowflake(it.targetId)) }

    private fun Map<String, Any?>.string(key: String): String =
        this[key]?.toString()?.trim()?.takeIf(String::isNotEmpty) ?: error("$key required")

    private fun Map<String, Any?>.optionalString(
        key: String,
        preserveBlank: Boolean = false,
    ): String? {
        if (!containsKey(key) || this[key] == null) return null
        val value = this[key].toString()
        return if (preserveBlank) value else value.trim().takeIf(String::isNotEmpty)
    }

    private fun Map<String, Any?>.boolean(key: String): Boolean? = this[key] as? Boolean

    private fun Map<String, Any?>.integer(key: String): Int? = (this[key] as? Number)?.toInt()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
        (this[key] as? List<*>)?.map { it as? Map<String, Any?> ?: error("$key must contain objects") }.orEmpty()

    private fun Map<String, Any?>.stringSet(key: String): Set<String> =
        (this[key] as? List<*>)?.map { it?.toString() ?: error("$key must contain strings") }?.toSet().orEmpty()

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValueOf(value.trim().uppercase().replace('-', '_'))

    private fun validSnowflake(value: String): Boolean =
        value.length in 17..20 && value.all(Char::isDigit)

    private fun respond(
        exchange: HttpExchange,
        code: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private data class SimulateRequest(
        val player: String = "",
        val message: String = "",
        val rawText: String? = null,
        val server: String? = "survival",
        val replyToBot: Boolean = false,
        val continuationWithBot: Boolean = false,
        val waitSeconds: Int = 45,
    )

    private data class DiscordSendHttpRequest(
        val channelId: String,
        val content: String,
        val replyToMessageId: String?,
        val confirmation: String,
    )

    companion object {
        private const val DISCORD_MESSAGE_MAX_LENGTH = 2_000
        private const val DISCORD_REQUEST_TIMEOUT_SECONDS = 15L
        private const val MAX_REQUEST_BODY_BYTES = 12 * 1024 * 1024
    }
}
