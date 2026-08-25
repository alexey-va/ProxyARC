package ru.arc.discord

import com.velocitypowered.api.command.CommandSource
import ru.arc.velocity.Velocity
import java.util.concurrent.CompletableFuture

internal sealed interface DiscordIdentityLookupResult {
    data class Linked(
        val link: DiscordIdentityLink,
        val diagnostic: DiscordRoleSyncDiagnostic?,
    ) : DiscordIdentityLookupResult

    data object Invalid : DiscordIdentityLookupResult

    data object NotLinked : DiscordIdentityLookupResult

    data object Ambiguous : DiscordIdentityLookupResult

    data object Unavailable : DiscordIdentityLookupResult
}

internal interface DiscordVerificationAdminGateway {
    fun lookupIdentity(query: String): DiscordIdentityLookupResult

    fun reconcileIdentity(
        link: DiscordIdentityLink,
        trigger: DiscordRoleSyncTrigger,
    ): CompletableFuture<DiscordRoleReconcileResult>

    fun unlinkIdentity(link: DiscordIdentityLink): CompletableFuture<DiscordVerificationWorkflowResult>
}

internal class DiscordVerificationAdminCommand(
    private val gatewayProvider: () -> DiscordVerificationAdminGateway? = { Velocity.discordBot?.adminGateway() },
    private val messagesProvider: () -> DiscordVerificationMessages = {
        Velocity.discordBot?.verificationMessages() ?: DiscordVerificationMessages.load()
    },
) {
    fun execute(
        source: CommandSource,
        args: List<String>,
    ) {
        val messages = messagesProvider()
        if (!source.hasPermission(PERMISSION)) {
            source.sendMessage(messages.minecraft("admin-no-permission"))
            return
        }
        val gateway = gatewayProvider()
        if (gateway == null) {
            source.sendMessage(messages.minecraft("admin-unavailable"))
            return
        }
        when (args.firstOrNull()?.lowercase()) {
            "status" -> status(source, gateway, messages, args)
            "sync" -> sync(source, gateway, messages, args)
            "unlink" -> unlink(source, gateway, messages, args)
            else -> source.sendMessage(messages.minecraft("admin-usage"))
        }
    }

    fun suggest(args: List<String>): List<String> =
        when (args.size) {
            0 -> ACTIONS
            1 -> ACTIONS.filter { it.startsWith(args[0], ignoreCase = true) }
            3 ->
                if (args[0].equals("unlink", true)) {
                    listOf("confirm").filter { it.startsWith(args[2], ignoreCase = true) }
                } else {
                    emptyList()
                }
            else -> emptyList()
        }

    private fun status(
        source: CommandSource,
        gateway: DiscordVerificationAdminGateway,
        messages: DiscordVerificationMessages,
        args: List<String>,
    ) {
        if (args.size != 2) {
            source.sendMessage(messages.minecraft("admin-usage"))
            return
        }
        when (val lookup = gateway.lookupIdentity(args[1])) {
            is DiscordIdentityLookupResult.Linked -> source.sendMessage(messages.adminStatus(lookup))
            DiscordIdentityLookupResult.Invalid -> source.sendMessage(messages.minecraft("admin-invalid-query"))
            DiscordIdentityLookupResult.NotLinked ->
                source.sendMessage(messages.minecraft("admin-not-linked", "query" to args[1]))
            DiscordIdentityLookupResult.Ambiguous -> source.sendMessage(messages.minecraft("admin-ambiguous-query"))
            DiscordIdentityLookupResult.Unavailable -> source.sendMessage(messages.minecraft("admin-unavailable"))
        }
    }

    private fun sync(
        source: CommandSource,
        gateway: DiscordVerificationAdminGateway,
        messages: DiscordVerificationMessages,
        args: List<String>,
    ) {
        if (args.size != 2) {
            source.sendMessage(messages.minecraft("admin-usage"))
            return
        }
        val lookup = gateway.lookupIdentity(args[1])
        if (lookup !is DiscordIdentityLookupResult.Linked) {
            sendLookupFailure(source, messages, args[1], lookup)
            return
        }
        gateway.reconcileIdentity(lookup.link, DiscordRoleSyncTrigger.ADMIN).whenComplete { result, error ->
            when {
                error != null || result == null ->
                    source.sendMessage(
                        messages.minecraft(
                            "admin-sync-failed",
                            "player_name" to lookup.link.playerName,
                            "status" to "failed",
                            "reason" to (error?.javaClass?.simpleName ?: "unknown"),
                        ),
                    )
                result.successful ->
                    source.sendMessage(
                        messages.minecraft(
                            "admin-sync-success",
                            "player_name" to lookup.link.playerName,
                            "status" to result.status.name.lowercase(),
                        ),
                    )
                else ->
                    source.sendMessage(
                        messages.minecraft(
                            "admin-sync-failed",
                            "player_name" to lookup.link.playerName,
                            "status" to result.status.name.lowercase(),
                            "reason" to result.reason.orEmpty().ifBlank { "unspecified" },
                        ),
                    )
            }
        }
    }

    private fun unlink(
        source: CommandSource,
        gateway: DiscordVerificationAdminGateway,
        messages: DiscordVerificationMessages,
        args: List<String>,
    ) {
        if (args.size < 2) {
            source.sendMessage(messages.minecraft("admin-usage"))
            return
        }
        if (args.size != 3 || !args[2].equals("confirm", true)) {
            val query = args[1].take(MAX_ECHO_LENGTH)
            source.sendMessage(messages.minecraft("admin-unlink-confirm", "query" to query))
            return
        }
        val lookup = gateway.lookupIdentity(args[1])
        if (lookup !is DiscordIdentityLookupResult.Linked) {
            sendLookupFailure(source, messages, args[1], lookup)
            return
        }
        gateway.unlinkIdentity(lookup.link).whenComplete { result, error ->
            val message =
                when {
                    error != null -> messages.minecraft("admin-unlink-failed")
                    result is DiscordVerificationWorkflowResult.Unlinked ->
                        messages.minecraft("admin-unlink-success", "player_name" to result.previousLink.playerName)
                    result is DiscordVerificationWorkflowResult.RoleFailure ->
                        messages.minecraft(
                            "admin-unlink-role-failure",
                            "status" to result.result.status.name.lowercase(),
                            "reason" to result.result.reason.orEmpty().ifBlank { "unspecified" },
                        )
                    result is DiscordVerificationWorkflowResult.NotLinked ->
                        messages.minecraft("admin-not-linked", "query" to args[1])
                    result is DiscordVerificationWorkflowResult.Conflict -> messages.minecraft("admin-conflict")
                    else -> messages.minecraft("admin-unlink-failed")
                }
            source.sendMessage(message)
        }
    }

    private fun sendLookupFailure(
        source: CommandSource,
        messages: DiscordVerificationMessages,
        query: String,
        lookup: DiscordIdentityLookupResult,
    ) {
        source.sendMessage(
            when (lookup) {
                DiscordIdentityLookupResult.Invalid -> messages.minecraft("admin-invalid-query")
                DiscordIdentityLookupResult.NotLinked -> messages.minecraft("admin-not-linked", "query" to query)
                DiscordIdentityLookupResult.Ambiguous -> messages.minecraft("admin-ambiguous-query")
                DiscordIdentityLookupResult.Unavailable -> messages.minecraft("admin-unavailable")
                is DiscordIdentityLookupResult.Linked -> error("linked lookup is not a failure")
            },
        )
    }

    companion object {
        const val PERMISSION = "arc.admin"
        private const val MAX_ECHO_LENGTH = 64
        private val ACTIONS = listOf("status", "sync", "unlink")
    }
}
