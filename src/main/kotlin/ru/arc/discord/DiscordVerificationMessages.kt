package ru.arc.discord

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import ru.arc.config.Config
import ru.arc.config.ProxyConfigs

/** Typed owner of every player-facing message in the Discord verification flow. */
internal class DiscordVerificationMessages(
    private val config: Config,
) {
    val identity: String get() = raw("identity").trim()
    val inviteUrl: String get() = raw("invite-url").trim()

    fun minecraft(
        key: String,
        vararg values: Pair<String, String>,
    ): Component {
        require(key in MINECRAFT_MESSAGE_KEYS) { "unknown Minecraft verification message: $key" }
        val body = parse(raw("minecraft.$key"), values.toMap())
        return parse(
            raw("minecraft.format"),
            components =
                mapOf(
                    "identity" to Component.text(identity),
                    "message" to body,
                ),
        )
    }

    fun discord(
        key: String,
        vararg values: Pair<String, String>,
    ): String {
        require(key in DISCORD_MESSAGE_KEYS) { "unknown Discord verification message: $key" }
        val body = replacePlain(raw("discord.$key"), values.toMap())
        return replacePlain(
            raw("discord.format"),
            mapOf(
                "identity" to identity,
                "message" to body,
            ),
        )
    }

    fun discordCommandDescription(key: String): String {
        require(key in DISCORD_COMMAND_DESCRIPTION_KEYS) { "unknown Discord command description: $key" }
        return raw("discord.commands.$key").trim()
    }

    fun challenge(
        code: String,
        expiresInMinutes: Long,
        recovery: Boolean,
    ): Component {
        require(CODE.matches(code)) { "invalid Discord verification code" }
        require(DiscordVerificationConfig.validInviteUrl(inviteUrl)) { "invalid Discord invite URL" }

        val copyEvent = ClickEvent.copyToClipboard(code)
        val codeComponent =
            interactive(
                parse(
                    raw("minecraft.challenge.code-format"),
                    components = mapOf("code" to Component.text(code)),
                ),
                click = copyEvent,
                hover = parse(raw("minecraft.challenge.code-hover")),
            )
        val codeRow =
            interactive(
                parse(
                    raw("minecraft.challenge.code-row"),
                    components = mapOf("code" to codeComponent),
                ),
                click = copyEvent,
                hover = parse(raw("minecraft.challenge.code-row-hover")),
            )
        val inviteLink =
            interactive(
                parse(raw("minecraft.challenge.invite-label")),
                click = ClickEvent.openUrl(inviteUrl),
                hover = parse(raw("minecraft.challenge.invite-hover")),
            )
        val titleKey = if (recovery) "recovery-title" else "link-title"
        val minuteWord = minuteWord(expiresInMinutes)
        val components =
            mapOf(
                "title" to parse(raw("minecraft.challenge.$titleKey")),
                "code_row" to codeRow,
                "copy_hint" to parse(raw("minecraft.challenge.copy-hint")),
                "invite_link" to inviteLink,
                "command_hint" to parse(raw("minecraft.challenge.command-hint")),
                "input_hint" to parse(raw("minecraft.challenge.input-hint")),
                "expiry" to
                    parse(
                        raw("minecraft.challenge.expiry"),
                        mapOf(
                            "minutes" to expiresInMinutes.toString(),
                            "minute_word" to minuteWord,
                        ),
                    ),
            )
        val lines = layout().map { parse(it, components = components) }
        return lines.drop(1).fold(lines.first()) { result, line ->
            result.append(Component.newline()).append(line)
        }
    }

    fun adminStatus(status: DiscordIdentityLookupResult.Linked): Component {
        val diagnostic = status.diagnostic
        val components =
            mapOf(
                "title" to parse(raw("minecraft.admin.status-title")),
                "minecraft" to
                    parse(
                        raw("minecraft.admin.status-minecraft"),
                        mapOf("player_name" to status.link.playerName),
                    ),
                "uuid" to
                    parse(
                        raw("minecraft.admin.status-uuid"),
                        mapOf("player_uuid" to status.link.playerUuid.toString()),
                    ),
                "discord" to
                    parse(
                        raw("minecraft.admin.status-discord"),
                        mapOf("discord_id" to status.link.discordUserId),
                    ),
                "sync" to
                    if (diagnostic == null) {
                        parse(raw("minecraft.admin.status-sync-never"))
                    } else {
                        parse(
                            raw("minecraft.admin.status-sync"),
                            mapOf(
                                "status" to diagnostic.result.status.name.lowercase(),
                                "trigger" to diagnostic.trigger.label,
                            ),
                        )
                    },
                "age" to
                    if (diagnostic == null) {
                        Component.empty()
                    } else {
                        parse(
                            raw("minecraft.admin.status-age"),
                            mapOf(
                                "seconds" to
                                    ((System.currentTimeMillis() - diagnostic.attemptedAt).coerceAtLeast(0L) / 1_000)
                                        .toString(),
                            ),
                        )
                    },
                "reason" to
                    if (diagnostic?.result?.reason == null) {
                        parse(raw("minecraft.admin.status-reason-none"))
                    } else {
                        parse(
                            raw("minecraft.admin.status-reason"),
                            mapOf("reason" to diagnostic.result.reason),
                        )
                    },
            )
        val lines = adminStatusLayout().map { parse(it, components = components) }
        return lines.drop(1).fold(lines.first()) { result, line ->
            result.append(Component.newline()).append(line)
        }
    }

    fun validate(requireInviteUrl: Boolean) {
        require(identity.isNotBlank() && identity.length <= 24) {
            "messages.identity must be 1..24 characters"
        }
        if (requireInviteUrl) {
            require(DiscordVerificationConfig.validInviteUrl(inviteUrl)) {
                "messages.invite-url must be a direct HTTPS discord.gg or discord.com/invite URL"
            }
        }
        REQUIRED_STRING_KEYS.forEach { key ->
            val value = raw(key)
            require(value.isNotBlank()) { "messages.$key must not be blank" }
            require(value.length <= MAX_TEMPLATE_LENGTH) { "messages.$key is too long" }
        }
        require("<identity>" in raw("minecraft.format") && "<message>" in raw("minecraft.format")) {
            "messages.minecraft.format must contain <identity> and <message>"
        }
        require("%identity%" in raw("discord.format") && "%message%" in raw("discord.format")) {
            "messages.discord.format must contain %identity% and %message%"
        }
        DISCORD_COMMAND_DESCRIPTION_KEYS.forEach { key ->
            require(discordCommandDescription(key).length in 1..100) {
                "messages.discord.commands.$key must be 1..100 characters"
            }
        }
        val layout = layout()
        require(layout.size in 3..32 && layout.first().isEmpty() && layout.last().isEmpty()) {
            "messages.minecraft.challenge.layout must frame the block with one blank line"
        }
        require(layout.filter(String::isNotEmpty).all { it.startsWith("  ") }) {
            "messages.minecraft.challenge.layout visible lines must start with two spaces"
        }
        CHALLENGE_LAYOUT_TAGS.forEach { tag ->
            require(layout.any { "<$tag>" in it }) {
                "messages.minecraft.challenge.layout must contain <$tag>"
            }
        }
        val adminLayout = adminStatusLayout()
        require(adminLayout.size in 5..16 && adminLayout.first().isEmpty() && adminLayout.last().isEmpty()) {
            "messages.minecraft.admin.status-layout must frame the block with one blank line"
        }
        require(adminLayout.filter(String::isNotEmpty).all { it.startsWith("  ") }) {
            "messages.minecraft.admin.status-layout visible lines must start with two spaces"
        }
        ADMIN_STATUS_LAYOUT_TAGS.forEach { tag ->
            require(adminLayout.any { "<$tag>" in it }) {
                "messages.minecraft.admin.status-layout must contain <$tag>"
            }
        }

        val dummy = arrayOf("player_name" to "PlayerName", "minutes" to "10")
        MINECRAFT_MESSAGE_KEYS.forEach { minecraft(it, *dummy) }
        DISCORD_MESSAGE_KEYS.forEach { key ->
            require(discord(key, *dummy).length <= 2_000) { "messages.discord.$key exceeds Discord's limit" }
        }
        if (requireInviteUrl) challenge("ABCD1234", 10, recovery = false)
        adminStatus(
            DiscordIdentityLookupResult.Linked(
                DiscordIdentityLink(
                    playerUuid = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    playerName = "PlayerName",
                    discordUserId = "1073279640912789595",
                    linkedAt = 1,
                    updatedAt = 1,
                ),
                DiscordRoleSyncDiagnostic(
                    attemptedAt = System.currentTimeMillis(),
                    trigger = DiscordRoleSyncTrigger.ADMIN,
                    result = DiscordRoleReconcileResult(DiscordRoleReconcileResult.Status.UNCHANGED),
                ),
            ),
        )
    }

    private fun minuteWord(value: Long): String {
        val key =
            if (value % 100 in 11..14) {
                "minute-many"
            } else {
                when (value % 10) {
                    1L -> "minute-one"
                    2L, 3L, 4L -> "minute-few"
                    else -> "minute-many"
                }
            }
        return raw("minecraft.challenge.$key").trim()
    }

    private fun layout(): List<String> = config.stringList("messages.minecraft.challenge.layout")

    private fun adminStatusLayout(): List<String> = config.stringList("messages.minecraft.admin.status-layout")

    private fun raw(path: String): String = config.string("messages.$path", "")

    private fun parse(
        template: String,
        values: Map<String, String> = emptyMap(),
        components: Map<String, Component> = emptyMap(),
    ): Component {
        val resolvers =
            buildList<TagResolver> {
                values.forEach { (name, value) -> add(Placeholder.unparsed(name, value)) }
                components.forEach { (name, component) -> add(Placeholder.component(name, component)) }
            }
        return MiniMessage.miniMessage().deserialize(template, TagResolver.resolver(resolvers))
    }

    private fun replacePlain(
        template: String,
        values: Map<String, String>,
    ): String = values.entries.fold(template) { result, (name, value) -> result.replace("%$name%", value) }

    private fun interactive(
        component: Component,
        click: ClickEvent,
        hover: Component,
    ): Component =
        component.children(component.children().map { interactive(it, click, hover) })
            .clickEvent(click)
            .hoverEvent(hover)

    companion object {
        private val CODE = Regex("[A-Z0-9]{6,16}")
        private const val MAX_TEMPLATE_LENGTH = 4_096

        internal val MINECRAFT_MESSAGE_KEYS =
            setOf(
                "only-player",
                "unavailable",
                "backend-required",
                "usage",
                "issue-failed-unchanged",
                "already-linked",
                "recovery-not-linked",
                "rate-limited",
                "issue-failed-saved",
                "status-not-linked",
                "status-linked",
                "unlink-failed-retry",
                "unlink-success",
                "unlink-not-linked",
                "unlink-role-failure",
                "unlink-failed-saved",
                "admin-no-permission",
                "admin-unavailable",
                "admin-usage",
                "admin-invalid-query",
                "admin-not-linked",
                "admin-ambiguous-query",
                "admin-sync-success",
                "admin-sync-failed",
                "admin-unlink-confirm",
                "admin-unlink-success",
                "admin-unlink-role-failure",
                "admin-unlink-failed",
                "admin-conflict",
            )
        internal val DISCORD_MESSAGE_KEYS =
            setOf(
                "verification-unavailable-saved",
                "status-not-linked",
                "status-linked",
                "verify-failed",
                "unlink-cancelled",
                "unlink-failed",
                "verified",
                "verified-nickname-skipped",
                "verified-role-sync-failed",
                "recovered",
                "recovered-nickname-skipped",
                "recovered-role-sync-failed",
                "unlinked",
                "rate-limited",
                "invalid-or-expired",
                "minecraft-already-linked",
                "discord-already-linked",
                "not-linked",
                "role-failure",
                "conflict",
                "unavailable",
            )
        private val DISCORD_COMMAND_DESCRIPTION_KEYS =
            setOf(
                "verify-description",
                "verify-code-description",
                "unlink-description",
                "unlink-confirm-description",
            )
        private val CHALLENGE_STRING_KEYS =
            setOf(
                "link-title",
                "recovery-title",
                "code-format",
                "code-row",
                "code-hover",
                "code-row-hover",
                "copy-hint",
                "invite-label",
                "invite-hover",
                "command-hint",
                "input-hint",
                "expiry",
                "minute-one",
                "minute-few",
                "minute-many",
            )
        private val ADMIN_STATUS_STRING_KEYS =
            setOf(
                "status-title",
                "status-minecraft",
                "status-uuid",
                "status-discord",
                "status-sync",
                "status-sync-never",
                "status-age",
                "status-reason",
                "status-reason-none",
            )
        private val REQUIRED_STRING_KEYS =
            setOf("identity", "minecraft.format", "discord.format") +
                MINECRAFT_MESSAGE_KEYS.map { "minecraft.$it" } +
                CHALLENGE_STRING_KEYS.map { "minecraft.challenge.$it" } +
                ADMIN_STATUS_STRING_KEYS.map { "minecraft.admin.$it" } +
                DISCORD_MESSAGE_KEYS.map { "discord.$it" } +
                DISCORD_COMMAND_DESCRIPTION_KEYS.map { "discord.commands.$it" }
        private val CHALLENGE_LAYOUT_TAGS =
            setOf("title", "code_row", "copy_hint", "invite_link", "command_hint", "input_hint", "expiry")
        private val ADMIN_STATUS_LAYOUT_TAGS =
            setOf("title", "minecraft", "uuid", "discord", "sync", "age", "reason")

        fun load(): DiscordVerificationMessages =
            DiscordVerificationMessages(ProxyConfigs.module("discord-verification.yml"))
    }
}
