package ru.arc.telegram

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver

/** Typed owner of the Minecraft-facing Telegram verification surface. */
internal class TelegramVerificationMessages(
    private val config: TelegramConfig,
) {
    private val identity: String get() = raw("identity").trim()
    private val botUrl: String? get() = TelegramConfig.botUrl(config.botUsername)

    fun minecraft(
        key: String,
        vararg values: Pair<String, String>,
    ): Component {
        require(key in MESSAGE_KEYS) { "unknown Telegram verification message: $key" }
        val body =
            parse(
                raw(key),
                values.toMap(),
                components =
                    mapOf(
                        // Keep the legacy tag resolvable while tracked configs migrate to the accurate name.
                        "bot_link" to communityLink("link-label", "link-hover"),
                        "community_link" to communityLink("link-label", "link-hover"),
                    ),
            )
        return parse(
            raw("format"),
            components =
                mapOf(
                    "identity" to Component.text(identity),
                    "message" to body,
                ),
        )
    }

    fun challenge(
        code: String,
        expiresInMinutes: Long,
    ): Component {
        val compactCode = code.replace("-", "")
        require(
            CODE.matches(compactCode) &&
                (code == compactCode || code == compactCode.chunked(4).joinToString("-")),
        ) {
            "invalid Telegram verification code"
        }
        val command = "/verify $code"
        val copyCommand = ClickEvent.copyToClipboard(command)
        val codeComponent =
            interactive(
                parse(
                    raw("challenge.code-format"),
                    components = mapOf("code" to Component.text(code)),
                ),
                click = copyCommand,
                hover = parse(raw("challenge.code-hover")),
            )
        val codeRow =
            interactive(
                parse(
                    raw("challenge.code-row"),
                    components = mapOf("code" to codeComponent),
                ),
                click = copyCommand,
                hover = parse(raw("challenge.code-row-hover")),
            )
        val commandComponent =
            interactive(
                parse(
                    raw("challenge.command-format"),
                    components = mapOf("command" to Component.text(command)),
                ),
                click = copyCommand,
                hover = parse(raw("challenge.command-hover")),
            )
        val components =
            mapOf(
                "title" to
                    Component.text(identity, NamedTextColor.WHITE)
                        .append(Component.space())
                        .append(parse(raw("challenge.title"))),
                "code_row" to codeRow,
                "copy_hint" to parse(raw("challenge.copy-hint")),
                "bot_link" to botLink("challenge.bot-link-label", "challenge.bot-link-hover"),
                "command_hint" to
                    parse(
                        raw("challenge.command-hint"),
                        components = mapOf("command" to commandComponent),
                    ),
                "expiry" to
                    parse(
                        raw("challenge.expiry"),
                        values =
                            mapOf(
                                "minutes" to expiresInMinutes.toString(),
                                "minute_word" to minuteWord(expiresInMinutes),
                            ),
                    ),
            )
        val lines = layout().map { parse(it, components = components) }
        return lines.drop(1).fold(lines.first()) { result, line ->
            result.append(Component.newline()).append(line)
        }
    }

    fun validate() {
        require(identity.isNotBlank() && identity.length <= 24) {
            "identity.minecraft.identity must be 1..24 characters"
        }
        require(TelegramConfig.botUrl(config.botUsername) != null) {
            "username must be a valid Telegram bot username"
        }
        REQUIRED_STRING_PATHS.forEach { path ->
            val value = raw(path)
            require(value.isNotBlank()) { "identity.minecraft.$path must not be blank" }
            require(value.length <= MAX_TEMPLATE_LENGTH) { "identity.minecraft.$path is too long" }
        }
        require("<identity>" in raw("format") && "<message>" in raw("format")) {
            "identity.minecraft.format must contain <identity> and <message>"
        }
        val layout = layout()
        require(layout.size in 3..24 && layout.first().isEmpty() && layout.last().isEmpty()) {
            "identity.minecraft.challenge.layout must frame the block with one blank line"
        }
        require(layout.filter(String::isNotEmpty).all { it.startsWith("  ") }) {
            "identity.minecraft.challenge.layout visible lines must start with two spaces"
        }
        CHALLENGE_LAYOUT_TAGS.forEach { tag ->
            require(layout.any { "<$tag>" in it }) {
                "identity.minecraft.challenge.layout must contain <$tag>"
            }
        }
        val dummy = arrayOf("player_name" to "PlayerName", "telegram_username" to "@telegram_user", "minutes" to "10")
        MESSAGE_KEYS.forEach { minecraft(it, *dummy) }
        challenge("ABCD-EFGH", 10)
    }

    private fun minuteWord(value: Long): String {
        val path =
            if (value % 100 in 11..14) {
                "challenge.minute-many"
            } else {
                when (value % 10) {
                    1L -> "challenge.minute-one"
                    2L, 3L, 4L -> "challenge.minute-few"
                    else -> "challenge.minute-many"
                }
            }
        return raw(path).trim()
    }

    private fun botLink(
        labelPath: String,
        hoverPath: String,
    ): Component =
        botUrl?.let { url ->
            interactive(
                parse(raw(labelPath)),
                click = ClickEvent.openUrl(url),
                hover = parse(raw(hoverPath)),
            )
        } ?: Component.empty()

    private fun communityLink(
        labelPath: String,
        hoverPath: String,
    ): Component =
        config.informationUrl?.let { url ->
            interactive(
                parse(raw(labelPath)),
                click = ClickEvent.openUrl(url),
                hover = parse(raw(hoverPath)),
            )
        } ?: Component.empty()

    private fun layout(): List<String> = config.minecraftIdentityLines("challenge.layout")

    private fun raw(path: String): String = config.minecraftIdentityString(path)

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

    private fun interactive(
        component: Component,
        click: ClickEvent,
        hover: Component,
    ): Component =
        component.children(component.children().map { interactive(it, click, hover) })
            .clickEvent(click)
            .hoverEvent(hover)

    private companion object {
        private val CODE = Regex("[A-Z0-9]{6,16}")
        private const val MAX_TEMPLATE_LENGTH = 4_096
        private val MESSAGE_KEYS =
            setOf(
                "only-player",
                "unavailable",
                "backend-required",
                "usage",
                "already-linked",
                "rate-limited",
                "status-linked",
                "status-not-linked",
                "unlink-success",
                "unlink-not-linked",
            )
        private val CHALLENGE_STRING_PATHS =
            setOf(
                "title",
                "code-format",
                "code-row",
                "code-hover",
                "code-row-hover",
                "copy-hint",
                "bot-link-label",
                "bot-link-hover",
                "command-format",
                "command-hover",
                "command-hint",
                "expiry",
                "minute-one",
                "minute-few",
                "minute-many",
            )
        private val REQUIRED_STRING_PATHS =
            setOf("identity", "format", "link-label", "link-hover") +
                MESSAGE_KEYS +
                CHALLENGE_STRING_PATHS.map { "challenge.$it" }
        private val CHALLENGE_LAYOUT_TAGS =
            setOf("title", "code_row", "copy_hint", "bot_link", "command_hint", "expiry")
    }
}
