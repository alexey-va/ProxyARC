package ru.arc.discord

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import ru.arc.velocity.Velocity
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

internal class DiscordIntegrationListener(
    private val config: DiscordIntegrationConfig,
    private val verification: DiscordVerificationService,
    private val store: DiscordIntegrationStore,
    private val notifications: DiscordNotificationService,
    private val events: DiscordGameEventService,
    private val linkProtection: DiscordLinkProtectionService?,
) : ListenerAdapter() {
    private val messages = config.messages

    fun registerCommands(snapshot: DiscordSessionSnapshot) {
        if (!config.enabled) return
        val guild = snapshot.jda.getGuildById(config.guildId)
        if (guild == null) {
            log.error("Discord integration guild is unavailable")
            return
        }
        val commands =
            listOf(
                Commands.slash("account", messages.text("commands.account")),
                Commands.slash("online", messages.text("commands.online")),
                Commands.slash("server", messages.text("commands.server"))
                    .addOption(OptionType.STRING, "name", messages.text("commands.server-option"), false),
                Commands.slash("player", messages.text("commands.player"))
                    .addOption(OptionType.STRING, "name", messages.text("commands.player-option"), true),
                Commands.slash("notifications", messages.text("commands.notifications")),
                Commands.slash("invite", messages.text("commands.invite"))
                    .addOption(OptionType.STRING, "player", messages.text("commands.invite-player"), true),
                Commands.slash("event", messages.text("commands.event"))
                    .addOption(OptionType.STRING, "action", messages.text("commands.event-action"), true)
                    .addOption(OptionType.STRING, "name", messages.text("commands.event-name"), false)
                    .addOption(OptionType.STRING, "description", messages.text("commands.event-description"), false)
                    .addOption(OptionType.STRING, "start", messages.text("commands.event-start"), false)
                    .addOption(OptionType.STRING, "winner", messages.text("commands.event-winner"), false)
                    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_EVENTS)),
            )
        commands.forEach { command ->
            guild.upsertCommand(command).queue(
                { log.info("Discord /{} command registered", command.name) },
                { error -> log.error("Could not register Discord /${command.name}", error) },
            )
        }
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (!config.enabled || event.guild?.id != config.guildId) return
        when (event.name) {
            "account" -> account(event)
            "online" -> online(event)
            "server" -> server(event)
            "player" -> player(event)
            "notifications" -> notifications(event)
            "invite" -> invite(event)
            "event" -> event(event)
        }
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        if (!config.enabled) return
        if (event.componentId.startsWith(DiscordLinkProtectionService.BUTTON_PREFIX)) {
            cancelLinkTransfer(event)
            return
        }
        if (event.guild?.id != config.guildId) return
        when (event.componentId) {
            BUTTON_ACCOUNT_SYNC -> syncAccount(event)
            BUTTON_ACCOUNT_UNLINK -> unlinkPrompt(event)
            BUTTON_ACCOUNT_UNLINK_CONFIRM -> unlinkAccount(event)
            BUTTON_ACCOUNT_RECOVERY -> recovery(event)
            DiscordGameEventService.BUTTON_JOIN,
            DiscordGameEventService.BUTTON_LEAVE,
            -> toggleEvent(event)
            else -> notificationKind(event.componentId)?.let { toggleNotification(event, it) }
        }
    }

    private fun cancelLinkTransfer(event: ButtonInteractionEvent) {
        val cancelled = linkProtection?.cancel(event.componentId, event.user.id) == true
        val reply =
            event.reply(
                messages.text(if (cancelled) "security-link-cancelled" else "security-link-cancel-missed"),
            )
        if (event.guild != null) reply.setEphemeral(true)
        reply.queue()
    }

    private fun account(event: SlashCommandInteractionEvent) {
        val lookup = verification.lookup(event.user.id)
        if (lookup !is DiscordIdentityLookupResult.Linked) {
            event.reply(messages.text("not-linked")).setEphemeral(true).queue()
            return
        }
        val snapshot = networkSnapshot()
        val server = snapshot.serverFor(lookup.link.playerName) ?: messages.text("label-offline")
        val roleNames = event.member?.roles?.map { it.name }?.take(8).orEmpty()
        val roleText = roleNames.takeIf(List<String>::isNotEmpty)?.joinToString(", ") ?: messages.text("label-none")
        val sync =
            lookup.diagnostic?.result?.status?.name?.lowercase(Locale.ROOT)
                ?.let { messages.text("sync-status.$it") }
                ?: messages.text("sync-status.never")
        val body =
            messages.text(
                "account-body",
                "player" to DiscordTextSafety.markdown(lookup.link.playerName, 16),
                "server" to DiscordTextSafety.markdown(server, 40),
                "linked_at" to (lookup.link.linkedAt / 1_000).toString(),
                "roles" to DiscordTextSafety.markdown(roleText, 300),
                "sync" to DiscordTextSafety.markdown(sync, 60),
            )
        event.reply("## ${messages.text("account-title")}\n$body")
            .addComponents(
                ActionRow.of(
                    Button.primary(BUTTON_ACCOUNT_SYNC, messages.text("account-sync")),
                    Button.secondary(BUTTON_ACCOUNT_RECOVERY, messages.text("account-recovery")),
                    Button.danger(BUTTON_ACCOUNT_UNLINK, messages.text("account-unlink")),
                ),
            ).setEphemeral(true)
            .queue()
    }

    private fun online(event: SlashCommandInteractionEvent) {
        val snapshot = networkSnapshot()
        val text =
            if (snapshot.players.isEmpty()) {
                messages.text("online-empty")
            } else {
                snapshot.players.groupBy { it.server ?: messages.text("label-connecting") }.toSortedMap().entries.joinToString("\n\n") {
                    (server, players) ->
                    messages.text(
                        "online-server-block",
                        "server" to DiscordTextSafety.markdown(server, 40),
                        "count" to players.size.toString(),
                        "players" to players.joinToString(", ") { DiscordTextSafety.markdown(it.name, 16) },
                    )
                }
            }
        event.reply("## ${messages.text("online-title")} • ${snapshot.online}\n$text").setEphemeral(true).queue()
    }

    private fun server(event: SlashCommandInteractionEvent) {
        val requested = event.getOption("name")?.asString?.trim()
        val snapshot = networkSnapshot()
        if (requested.isNullOrEmpty()) {
            val all = snapshot.knownServers.sorted().joinToString("\n") { name ->
                val count = snapshot.onServer(name).size
                messages.text(
                    "server-list-item",
                    "server" to DiscordTextSafety.markdown(name, 40),
                    "count" to count.toString(),
                )
            }.ifBlank { messages.text("online-empty") }
            event.reply(all).setEphemeral(true).queue()
            return
        }
        val actual = snapshot.knownServers.firstOrNull { it.equals(requested, true) }
        if (actual == null) {
            event.reply(messages.text("server-not-found", "server" to DiscordTextSafety.markdown(requested, 40)))
                .setEphemeral(true).queue()
            return
        }
        val players = snapshot.onServer(actual)
        val text =
            players.joinToString(", ") { DiscordTextSafety.markdown(it.name, 16) }
                .ifBlank { messages.text("label-nobody") }
        event.reply(
            messages.text(
                "server-body",
                "server" to DiscordTextSafety.markdown(actual, 40),
                "count" to players.size.toString(),
                "players" to text,
            ),
        )
            .setEphemeral(true).queue()
    }

    private fun player(event: SlashCommandInteractionEvent) {
        val query = event.getOption("name")?.asString?.trim().orEmpty()
        if (!PLAYER_NAME.matches(query)) {
            event.reply(messages.text("player-not-found", "player" to DiscordTextSafety.markdown(query, 16)))
                .setEphemeral(true).queue()
            return
        }
        val snapshot = networkSnapshot()
        val online = snapshot.players.firstOrNull { it.name.equals(query, true) }
        val linked = verification.lookup(query)
        val knownName = online?.name ?: (linked as? DiscordIdentityLookupResult.Linked)?.link?.playerName
        if (knownName == null) {
            event.reply(messages.text("player-not-found", "player" to DiscordTextSafety.markdown(query, 16)))
                .setEphemeral(true).queue()
            return
        }
        event.reply(
            messages.text(
                "player-body",
                "player" to DiscordTextSafety.markdown(knownName, 16),
                "status" to messages.text(if (online == null) "label-offline" else "label-online"),
                "server" to DiscordTextSafety.markdown(online?.server ?: "—", 40),
                "verified" to messages.text(if (linked is DiscordIdentityLookupResult.Linked) "label-verified" else "label-unverified"),
            ),
        ).setEphemeral(true).queue()
    }

    private fun notifications(event: SlashCommandInteractionEvent) {
        if (verification.lookup(event.user.id) !is DiscordIdentityLookupResult.Linked) {
            event.reply(messages.text("not-linked")).setEphemeral(true).queue()
            return
        }
        replyPreferences(event.user.id, event::reply)
    }

    private fun invite(event: SlashCommandInteractionEvent) {
        val sender = verification.lookup(event.user.id)
        if (sender !is DiscordIdentityLookupResult.Linked) {
            event.reply(messages.text("not-linked")).setEphemeral(true).queue()
            return
        }
        val target = event.getOption("player")?.asString?.trim().orEmpty()
        if (!PLAYER_NAME.matches(target) || target.equals(sender.link.playerName, true)) {
            event.reply(messages.text("invite-unavailable")).setEphemeral(true).queue()
            return
        }
        val server = networkSnapshot().serverFor(sender.link.playerName)
        if (server == null) {
            event.reply(messages.text("invite-sender-offline")).setEphemeral(true).queue()
            return
        }
        val body =
            messages.text(
                "invite-body",
                "sender" to DiscordTextSafety.markdown(sender.link.playerName, 16),
                "server" to DiscordTextSafety.markdown(server, 40),
            )
        event.deferReply(true).queue { hook ->
            notifications.notifyInvite(target, body).whenComplete { delivered, error ->
                hook.editOriginal(
                    messages.text(if (delivered == true && error == null) "invite-sent" else "invite-unavailable"),
                ).queue()
            }
        }
    }

    private fun event(event: SlashCommandInteractionEvent) {
        if (event.member?.hasPermission(Permission.MANAGE_EVENTS) != true) {
            event.reply(messages.text("event-permission")).setEphemeral(true).queue()
            return
        }
        when (event.getOption("action")?.asString?.trim()?.lowercase(Locale.ROOT)) {
            "create" -> createEvent(event)
            "finish" -> finishEvent(event, cancelled = false)
            "cancel" -> finishEvent(event, cancelled = true)
            else -> event.reply(messages.text("event-action-help")).setEphemeral(true).queue()
        }
    }

    private fun createEvent(event: SlashCommandInteractionEvent) {
        val name = event.getOption("name")?.asString?.trim().orEmpty()
        val description = event.getOption("description")?.asString?.trim().orEmpty()
        val startsAt = parseStart(event.getOption("start")?.asString)
        if (name.isBlank() || description.isBlank() || startsAt == null || startsAt < System.currentTimeMillis() - 60_000) {
            event.reply(messages.text("event-create-help"))
                .setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue { hook ->
            events.create(name, description, startsAt).whenComplete { created, error ->
                val text =
                    if (error != null || created == null) messages.text("account-operation-failed")
                    else messages.text("event-created", "event" to DiscordTextSafety.markdown(created.name, 80))
                hook.editOriginal(text).queue()
            }
        }
    }

    private fun finishEvent(
        event: SlashCommandInteractionEvent,
        cancelled: Boolean,
    ) {
        val winner = event.getOption("winner")?.asString?.trim()?.takeIf(PLAYER_NAME::matches)
        if (!cancelled && winner == null) {
            event.reply(messages.text("event-winner-help")).setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue { hook ->
            events.finish(winner, cancelled).whenComplete { result, error ->
                val text =
                    when {
                        error != null -> messages.text("account-operation-failed")
                        result == null -> messages.text("event-none")
                        result.failedDiscordOperations > 0 ->
                            messages.text(
                                "event-finished-with-warnings",
                                "event" to DiscordTextSafety.markdown(result.event.name, 80),
                                "failures" to result.failedDiscordOperations.toString(),
                            )
                        cancelled ->
                            messages.text(
                                "event-cancelled",
                                "event" to DiscordTextSafety.markdown(result.event.name, 80),
                            )
                        else ->
                            messages.text(
                                "event-finished",
                                "event" to DiscordTextSafety.markdown(result.event.name, 80),
                            )
                    }
                hook.editOriginal(text).queue()
            }
        }
    }

    private fun syncAccount(event: ButtonInteractionEvent) {
        val lookup = verification.lookup(event.user.id)
        if (lookup !is DiscordIdentityLookupResult.Linked) {
            event.reply(messages.text("not-linked")).setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue { hook ->
            verification.reconcilePlayer(lookup.link.playerUuid, lookup.link.playerName, DiscordRoleSyncTrigger.ADMIN)
                .whenComplete { result, error ->
                    val text =
                        if (error == null && result?.successful == true) messages.text("account-synced")
                        else messages.text(
                            "account-sync-failed",
                            "reason" to DiscordTextSafety.markdown(result?.reason ?: error?.javaClass?.simpleName ?: "unknown", 100),
                        )
                    hook.editOriginal(text).queue()
                }
        }
    }

    private fun unlinkPrompt(event: ButtonInteractionEvent) {
        event.reply(messages.text("account-unlink-confirm"))
            .addComponents(ActionRow.of(Button.danger(BUTTON_ACCOUNT_UNLINK_CONFIRM, messages.text("account-unlink"))))
            .setEphemeral(true).queue()
    }

    private fun unlinkAccount(event: ButtonInteractionEvent) {
        val lookup = verification.lookup(event.user.id)
        if (lookup !is DiscordIdentityLookupResult.Linked) {
            event.reply(messages.text("not-linked")).setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue { hook ->
            verification.unlinkExpected(lookup.link).whenComplete { result, error ->
                val text =
                    if (error == null && result is DiscordVerificationWorkflowResult.Unlinked) {
                        store.recordSecurityEvent("unlink-discord", event.user.id, lookup.link.playerName)
                        messages.text("account-unlinked")
                    } else {
                        messages.text("account-operation-failed")
                    }
                hook.editOriginal(text).queue()
            }
        }
    }

    private fun recovery(event: ButtonInteractionEvent) {
        val lookup = verification.lookup(event.user.id)
        if (lookup !is DiscordIdentityLookupResult.Linked) {
            event.reply(messages.text("not-linked")).setEphemeral(true).queue()
            return
        }
        val active = store.activeRecoveryRequest(event.user.id)
        if (active != null) {
            event.reply(messages.text("recovery-active", "request" to active.id)).setEphemeral(true).queue()
            return
        }
        val request = store.createRecoveryRequest(lookup.link, config.recoveryRequestTtlSeconds)
        notifications.alert(
            messages.text(
                "recovery-alert",
                "player" to DiscordTextSafety.markdown(request.playerName, 16),
                "discord" to request.discordUserId,
                "request" to request.id,
                "expires_at" to (request.expiresAt / 1_000).toString(),
            ),
        )
        event.reply(
            messages.text(
                "recovery-created",
                "request" to request.id,
                "minutes" to (config.recoveryRequestTtlSeconds / 60).toString(),
            ),
        ).setEphemeral(true).queue()
    }

    private fun toggleEvent(event: ButtonInteractionEvent) {
        if (verification.lookup(event.user.id) !is DiscordIdentityLookupResult.Linked) {
            event.reply(messages.text("not-linked")).setEphemeral(true).queue()
            return
        }
        val wantsJoin = event.componentId == DiscordGameEventService.BUTTON_JOIN
        val active = events.activeEvent()
        val alreadyJoined = active?.participantDiscordIds?.contains(event.user.id) == true
        if (active == null || wantsJoin == alreadyJoined) {
            val text =
                when {
                    active == null -> messages.text("event-none")
                    alreadyJoined -> messages.text("event-joined", "event" to DiscordTextSafety.markdown(active.name, 80))
                    else -> messages.text("event-left", "event" to DiscordTextSafety.markdown(active.name, 80))
                }
            event.reply(text).setEphemeral(true).queue()
            return
        }
        event.deferReply(true).queue { hook ->
            events.toggleParticipation(event.user.id).whenComplete { result, error ->
                val text =
                    when {
                        error != null || result == null -> messages.text("account-operation-failed")
                        result.joined -> messages.text("event-joined", "event" to DiscordTextSafety.markdown(result.event.name, 80))
                        else -> messages.text("event-left", "event" to DiscordTextSafety.markdown(result.event.name, 80))
                    }
                hook.editOriginal(text).queue()
            }
        }
    }

    private fun toggleNotification(
        event: ButtonInteractionEvent,
        kind: DiscordNotificationKind,
    ) {
        if (verification.lookup(event.user.id) !is DiscordIdentityLookupResult.Linked) {
            event.reply(messages.text("not-linked")).setEphemeral(true).queue()
            return
        }
        notifications.toggle(event.user.id, kind)
        replyPreferences(event.user.id, event::reply)
    }

    private fun replyPreferences(
        discordUserId: String,
        reply: (String) -> net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction,
    ) {
        val preferences = notifications.preferences(discordUserId)
        val buttons = DiscordNotificationKind.entries.map { kind ->
            val enabled = preferences.enabled(kind)
            val label = "${messages.text(notificationLabel(kind))}: ${messages.text(if (enabled) "notification-enabled" else "notification-disabled")}".take(80)
            val id = BUTTON_NOTIFICATION_PREFIX + kind.name.lowercase(Locale.ROOT)
            if (enabled) Button.success(id, label) else Button.secondary(id, label)
        }
        reply("## ${messages.text("notifications-title")}\n${messages.text("notifications-body")}")
            .addComponents(ActionRow.of(buttons.take(5)), ActionRow.of(buttons.drop(5)))
            .setEphemeral(true).queue()
    }

    private fun networkSnapshot(): DiscordNetworkSnapshot =
        Velocity.proxyServer?.let(DiscordNetworkSnapshot::capture)
            ?: DiscordNetworkSnapshot(emptyList(), emptySet())

    private fun parseStart(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.length in 10..40 } ?: return null
        return try {
            LocalDateTime.parse(value, EVENT_DATE).atZone(MOSCOW).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DiscordIntegrationListener::class.java)
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        private val MOSCOW = ZoneId.of("Europe/Moscow")
        private val EVENT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private const val BUTTON_ACCOUNT_SYNC = "rc:account:sync"
        private const val BUTTON_ACCOUNT_UNLINK = "rc:account:unlink"
        private const val BUTTON_ACCOUNT_UNLINK_CONFIRM = "rc:account:unlink-confirm"
        private const val BUTTON_ACCOUNT_RECOVERY = "rc:account:recovery"
        private const val BUTTON_NOTIFICATION_PREFIX = "rc:notification:"

        private fun notificationKind(componentId: String): DiscordNotificationKind? {
            if (!componentId.startsWith(BUTTON_NOTIFICATION_PREFIX)) return null
            return runCatching {
                DiscordNotificationKind.valueOf(componentId.removePrefix(BUTTON_NOTIFICATION_PREFIX).uppercase(Locale.ROOT))
            }.getOrNull()
        }

        private fun notificationLabel(kind: DiscordNotificationKind): String =
            when (kind) {
                DiscordNotificationKind.MENTIONS -> "notification-mentions"
                DiscordNotificationKind.AUCTION -> "notification-auction"
                DiscordNotificationKind.TICKETS -> "notification-tickets"
                DiscordNotificationKind.PUNISHMENTS -> "notification-punishments"
                DiscordNotificationKind.EVENTS -> "notification-events"
                DiscordNotificationKind.INVITES -> "notification-invites"
            }
    }
}
