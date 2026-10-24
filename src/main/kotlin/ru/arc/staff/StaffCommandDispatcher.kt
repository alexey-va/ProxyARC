package ru.arc.staff

import com.google.gson.Gson
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.event.command.CommandExecuteEvent
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.permission.Tristate
import net.kyori.adventure.identity.Identity
import net.kyori.adventure.pointer.Pointers
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

internal class StaffCommandDispatcher(private val proxy: ProxyServer, private val config: StaffBridgeConfig) : AutoCloseable {
    private val httpDelegate = lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build() }
    private val http by httpDelegate
    private val gson = Gson()
    private val active = AtomicBoolean(true)

    fun execute(requestId: UUID, actor: StaffActor, command: StaffCommand): CompletableFuture<StaffExecutionResult> {
        if (!active.get()) return CompletableFuture.completedFuture(StaffExecutionResult(false, emptyList()))
        val currentActor = actor.current()?.takeIf { it.identity == actor.identity }
            ?: return CompletableFuture.completedFuture(StaffExecutionResult(false, emptyList()))
        if (command.permission != null && !currentActor.permission(command.permission)) {
            return CompletableFuture.completedFuture(StaffExecutionResult(false, emptyList()))
        }
        if (command.server == "proxy") {
            val capture = if (currentActor.admin) StaffCommandSource(currentActor, active::get) else null
            val source: CommandSource = capture ?: proxy.getPlayer(currentActor.identity.uuid).orElse(null)
                ?: return CompletableFuture.completedFuture(StaffExecutionResult(false, listOf(config.message("online-required"))))
            // Never use executeImmediatelyAsync or the console source: native plugin permission checks must run.
            if (!proxy.commandManager.hasCommand(command.line.substringBefore(' '))) {
                return CompletableFuture.completedFuture(StaffExecutionResult(false, emptyList()))
            }
            return proxy.commandManager.executeAsync(source, command.line).thenApply { dispatched ->
                StaffExecutionResult(dispatched, capture?.output() ?: if (dispatched) listOf(config.message("minecraft-output")) else emptyList())
            }
        }
        if (!currentActor.admin) return CompletableFuture.completedFuture(StaffExecutionResult(false, emptyList()))
        val backend = requireNotNull(config.backends[command.server])
        val body = gson.toJson(mapOf("requestId" to requestId.toString(), "actorUuid" to actor.identity.uuid.toString(), "command" to command.line))
        val request = HttpRequest.newBuilder(backend.baseUrl.resolve("/ops/staff-command"))
            .timeout(Duration.ofSeconds(25)).header("Authorization", "Bearer ${backend.token}")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), 16_384))
            .thenApply { response ->
                val parsed = com.google.gson.JsonParser.parseString(response.body()).asJsonObject
                if (response.statusCode() !in 200..299 || parsed.get("ok")?.asBoolean != true) {
                    // Known backend denials have no command output; retain no server-provided error text.
                    check(response.statusCode() in setOf(400, 401, 403, 409, 422, 429)) { "Unknown backend command outcome" }
                    StaffExecutionResult(false, emptyList())
                } else {
                    check(parsed.get("requestId")?.asString == requestId.toString()) { "Mismatched command response" }
                    val output = parsed.getAsJsonArray("output")?.take(24)?.map { it.asString.take(512) }.orEmpty()
                    StaffExecutionResult(parsed.get("dispatched")?.asBoolean == true, output)
                }
            }
    }

    override fun close() {
        active.set(false)
        if (httpDelegate.isInitialized()) http.shutdownNow()
    }
}

/** Deliberately not ConsoleCommandSource or Player. Identity is visible through native Adventure pointers. */
internal class StaffCommandSource(private val actor: StaffActor, private val active: () -> Boolean = { true }) : CommandSource {
    private val lines = ArrayList<String>()
    override fun getPermissionValue(permission: String): Tristate =
        if (active() && actor.current()?.takeIf { it.admin }?.permission?.invoke(permission) == true) Tristate.TRUE else Tristate.FALSE
    override fun pointers(): Pointers = Pointers.builder().withStatic(Identity.UUID, actor.identity.uuid).withStatic(Identity.NAME, actor.identity.name).build()
    override fun sendMessage(message: Component) {
        synchronized(lines) {
            if (lines.size < 24) lines += PlainTextComponentSerializer.plainText().serialize(message).take(512)
        }
    }
    fun output(): List<String> = synchronized(lines) { lines.toList() }

    companion object {
        fun guard(event: CommandExecuteEvent) {
            val source = event.commandSource as? StaffCommandSource ?: return
            if (!runCatching { source.active() && source.actor.current()?.admin == true }.getOrDefault(false)) {
                event.result = CommandExecuteEvent.CommandResult.denied()
            }
        }
    }
}
