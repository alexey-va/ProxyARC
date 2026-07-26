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

class ProxyOpsHttpServer(
    private val executorFactory: () -> ExecutorService = {
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "proxyarc-ops-http").apply { isDaemon = true }
        }
    },
    private val configProvider: () -> ProxyOpsHttpConfig = ProxyOpsHttpConfig::current,
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
            else -> respond(exchange, 404, ProxyOpsJson.error("Not found: /ops/$path"))
        }
    }

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
        }

    private fun readBody(exchange: HttpExchange): String {
        val bytes = exchange.requestBody.readAllBytes()
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
}
