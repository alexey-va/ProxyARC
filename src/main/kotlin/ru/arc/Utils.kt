package ru.arc

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import ru.arc.config.ConfigManager
import ru.arc.util.TextUtils
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
    fun mm(s: String): Component = TextUtils.mm(s)

    @JvmStatic
    fun mm(s: String, strip: Boolean, vararg replacers: String): Component =
        TextUtils.mm(s, strip, *replacers)

    @JvmStatic
    fun mm(s: String, resolver: TagResolver): Component = TextUtils.mm(s, resolver)

    @JvmStatic
    fun mm(s: String, strip: Boolean): Component = TextUtils.mm(s, strip)

    @JvmStatic
    fun legacy(message: String): Component = TextUtils.legacy(message)

    @JvmStatic
    fun plain(component: Component): String = TextUtils.plain(component)

    @JvmStatic
    fun plain(minimessage: String): String = TextUtils.plain(minimessage)

    @JvmStatic
    fun strip(component: Component?): Component? = TextUtils.strip(component)

    @JvmStatic
    fun parseTime(duration: Long, unit: TimeUnit): Component {
        val s = String.format(
            "&a%d &eчасов",
            unit.toHours(duration),
        )
        return TextUtils.legacy(s)
    }

    @JvmStatic
    fun error(): Component =
        Component.text("Произошла ошибка!", NamedTextColor.RED)
            .append(Component.text(" Обратитесь к администратуору", NamedTextColor.GRAY))
}
