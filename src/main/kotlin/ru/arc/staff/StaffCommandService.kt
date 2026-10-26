package ru.arc.staff

import org.slf4j.LoggerFactory
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.redis.safety.MessageClaimResult
import ru.arc.redis.safety.RecentMessageDeduplicator
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class StaffRequest(
    val eventId: String,
    val provider: String,
    val providerUserId: String,
    val createdAt: Long,
    val input: String,
    val adminOnly: Boolean = false,
)

internal data class StaffExecutionResult(val dispatched: Boolean, val output: List<String>)

/** The caller's platform identity stays authoritative until the final privileged dispatch. */
internal class StaffCommandService(
    private val config: StaffBridgeConfig,
    dataRoot: Path,
    private val identity: (String, String) -> StaffIdentity?,
    private val authorize: (StaffIdentity) -> CompletableFuture<StaffActor?>,
    private val dispatch: (UUID, StaffActor, StaffCommand) -> CompletableFuture<StaffExecutionResult>,
    private val auditConsole: (StaffActor, StaffCommand) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val ledger = StaffCommandLedger(dataRoot)
    private val worker = BoundedAsyncExecutor("staff-command", 2, 32)
    private val rate = RecentMessageDeduplicator(2_000, 4096)
    private val closed = AtomicBoolean(false)

    fun execute(request: StaffRequest): CompletableFuture<String> {
        if (closed.get()) return CompletableFuture.completedFuture(config.message("unavailable"))
        return worker.submit {
            if (request.provider !in setOf("discord", "telegram") || !request.providerUserId.matches(Regex("[0-9]{1,20}")) ||
                !request.eventId.matches(Regex("[a-z0-9:._-]{1,256}"))) return@submit config.message("invalid")
            fun rejected(outcome: String, actor: UUID? = null): String {
                log.info("Staff command rejected provider={} user={} actor={} event={} outcome={}",
                    request.provider, request.providerUserId, actor, request.eventId, outcome)
                return config.message(outcome)
            }
            val now = clock()
            if (now - request.createdAt !in -5_000..120_000) return@submit rejected("stale")
            val command = StaffCommands.parse(request.input)?.copy(adminOnly = request.adminOnly) ?: return@submit rejected("invalid")
            val linked = identity(request.provider, request.providerUserId) ?: return@submit rejected("denied")
            val actor = authorize(linked).get(5, TimeUnit.SECONDS) ?: return@submit rejected("denied", linked.uuid)
            if (closed.get() || identity(request.provider, request.providerUserId) != linked) return@submit rejected("denied", linked.uuid)
            if ((command.adminOnly || command.server != "proxy") && !actor.admin) return@submit rejected("admin-required", linked.uuid)
            if (command.server != "proxy" && command.server !in config.backends) return@submit rejected("invalid", linked.uuid)
            if (command.permission != null && !actor.permission(command.permission)) return@submit rejected("denied", linked.uuid)
            if (!ledger.claim(request.eventId, now)) return@submit rejected("duplicate", linked.uuid)
            if (rate.claim(linked.uuid.toString(), now) != MessageClaimResult.ACCEPTED) return@submit rejected("rate-limited", linked.uuid)
            // Once dispatch starts, failures/timeouts are ambiguous and the reserved event is never reissued.
            if (closed.get() || identity(request.provider, request.providerUserId) != linked) return@submit rejected("denied", linked.uuid)
            val currentActor = actor.current()?.takeIf { it.identity == linked } ?: return@submit rejected("denied", linked.uuid)
            if ((command.adminOnly || command.server != "proxy") && !currentActor.admin) return@submit rejected("admin-required", linked.uuid)
            if (command.permission != null && !currentActor.permission(command.permission)) return@submit rejected("denied", linked.uuid)
            val id = UUID.nameUUIDFromBytes(request.eventId.toByteArray(Charsets.UTF_8))
            try {
                val result = dispatch(id, currentActor, command).get(30, TimeUnit.SECONDS)
                log.info("Staff command provider={} actor={} event={} action={} server={} dispatched={}",
                    request.provider, linked.uuid, request.eventId, command.action, command.server, result.dispatched)
                if (command.server == "proxy" && currentActor.admin && result.dispatched) auditConsole(currentActor, command)
                val output = result.output.joinToString("\n").take(1600)
                when {
                    !result.dispatched -> config.message("not-dispatched") + output.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
                    output.isNotEmpty() -> config.message("accepted") + "\n" + output
                    else -> config.message("no-output")
                }
            } catch (_: Exception) {
                log.warn("Staff command outcome unknown provider={} actor={} event={} action={} server={}",
                    request.provider, linked.uuid, request.eventId, command.action, command.server)
                config.message("unknown-outcome")
            }
        }.exceptionally { error ->
            log.warn("Staff command rejected before dispatch cause={}", error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName)
            config.message("unavailable")
        }
    }

    override fun close() { closed.set(true); worker.close() }
    private companion object { val log = LoggerFactory.getLogger(StaffCommandService::class.java) }
}
