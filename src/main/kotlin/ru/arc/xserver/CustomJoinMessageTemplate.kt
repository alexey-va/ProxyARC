package ru.arc.xserver

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/** Legacy suffixes and full MiniMessage templates mirrored at the Redis wire boundary. */
internal object CustomJoinMessageTemplate {
    const val MAX_LENGTH = 512
    const val MAX_VISIBLE_LENGTH = 160
    const val MAX_SAVED = 10
    const val PLAYER = "%player_name%"
    const val FULL_PREFIX = "<reset>"
    private val colors = "black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white|#[0-9a-f]{6}"
    private val color = "(?:$colors)"
    private val decoration = "(?:bold|b|italic|em|i|underlined|u|strikethrough|st|obfuscated|obf)"
    private val phase = "-?(?:[0-9]+(?:\\.[0-9]+)?)"
    private val tag = Regex("(?:/?$color|/?(?:color|colour|c)(?::$color)?|/?$decoration(?::(?:true|false))?|!$decoration|reset|/?gradient(?::$color)*(?::$phase)?|/?rainbow(?::!?$phase|:!)?|/?font(?::[a-z0-9_.-]+(?::[a-z0-9_./-]+)?)?)", RegexOption.IGNORE_CASE)
    private val tags = Regex("<([^<>]+)>")
    private val miniMessage = MiniMessage.builder().tags(
        TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations(), StandardTags.reset(),
            StandardTags.gradient(), StandardTags.rainbow(), StandardTags.font(),
        ),
    ).build()

    fun valid(raw: String): Boolean = runCatching { normalize(raw) }.isSuccess

    fun normalize(raw: String): String {
        require(raw.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() || it in setOf('&', '§', '\\') })
        val text = raw.trim()
        require(text.isNotBlank() && text.length <= MAX_LENGTH)
        if (PLAYER !in text) {
            require(text.length <= 120 && text.none { it in setOf('<', '>', '%', '#') })
            return text
        }
        require(text.windowed(PLAYER.length).count { it == PLAYER } == 1 && '%' !in text.replace(PLAYER, ""))
        require(tags.findAll(text).all { tag.matches(it.groupValues[1]) })
        val literal = tags.replace(text, "")
        require('<' !in literal && '>' !in literal)
        val plain = PlainTextComponentSerializer.plainText().serialize(render(text, "Player1234567890"))
        require(plain.length <= MAX_VISIBLE_LENGTH && '<' !in plain && '>' !in plain)
        return text
    }

    fun selectionKey(message: String): String = if (PLAYER in message) FULL_PREFIX + message else "$PLAYER $message"

    fun editable(message: String): String = if (PLAYER in message) message else "$PLAYER $message"

    fun render(message: String, playerName: String): Component =
        Component.empty().decoration(TextDecoration.ITALIC, false)
            .append(miniMessage.deserialize(editable(message).replace(PLAYER, playerName)))
}
