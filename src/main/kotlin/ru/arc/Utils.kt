package ru.arc

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.ConfigManager
import java.util.concurrent.TimeUnit

object Utils {
    @JvmStatic
    fun formatTime(millis: Long): String {
        val days = millis / (24 * 60 * 60 * 1000)
        val hours = (millis % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)
        val minutes = (millis % (60 * 60 * 1000)) / (60 * 1000)

        return String.format(
            ConfigManager.of(CommonCore.folder!!, "config.yml")
                .string("time-format", "%dд %dч %dм"),
            days,
            hours,
            minutes,
        )
    }

    @JvmStatic
    fun mm(s: String): Component = MiniMessage.miniMessage().deserialize(s)

    @JvmStatic
    fun mm(s: String, strip: Boolean, vararg replacers: String): Component {
        var result = s
        var i = 0
        while (i < replacers.size) {
            if (replacers.size < i + 1) break
            result = result.replace(replacers[i], replacers[i + 1])
            i += 2
        }
        return mm(result, strip)
    }

    @JvmStatic
    fun mm(s: String, resolver: TagResolver): Component =
        MiniMessage.miniMessage().deserialize(s, resolver)

    @JvmStatic
    fun mm(s: String, strip: Boolean): Component {
        val component = MiniMessage.miniMessage().deserialize(s)
        return if (strip) strip(component) ?: component else component
    }

    @JvmStatic
    fun legacy(message: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(message)

    @JvmStatic
    fun plain(component: Component): String =
        PlainTextComponentSerializer.plainText().serialize(component)

    @JvmStatic
    fun plain(minimessage: String): String =
        PlainTextComponentSerializer.plainText().serialize(mm(minimessage))

    @JvmStatic
    fun strip(component: Component?): Component? {
        if (component == null) return null
        return component.decoration(TextDecoration.ITALIC, false)
    }

    @JvmStatic
    fun parseTime(duration: Long, unit: TimeUnit): Component {
        val s = String.format(
            "&a%d &eчасов",
            unit.toHours(duration),
        )
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s)
    }

    @JvmStatic
    fun error(): Component =
        Component.text("Произошла ошибка!", NamedTextColor.RED)
            .append(Component.text(" Обратитесь к администратуору", NamedTextColor.GRAY))
}
