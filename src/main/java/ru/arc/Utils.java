package ru.arc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import ru.arc.CommonCore;
import ru.arc.config.ConfigManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Utils {

    public static String formatTime(long millis) {
        long days = millis / (24 * 60 * 60 * 1000);
        long hours = (millis % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000);
        long minutes = (millis % (60 * 60 * 1000)) / (60 * 1000);

        return String.format(ConfigManager.of(CommonCore.folder, "config.yml")
                .string("time-format", "%dд %dч %dм"), days, hours, minutes);
    }

    public static Component mm(String s) {
        return MiniMessage.miniMessage().deserialize(s);
    }

    public static Component mm(String s, boolean strip, String... replacers) {
        Map<String, String> replace = new HashMap<>();
        for (int i = 0; i < replacers.length; i += 2) {
            if (replacers.length < i + 1) break;
            replace.put(replacers[i], replacers[i + 1]);
        }
        for (Map.Entry<String, String> entry : replace.entrySet()) {
            s = s.replace(entry.getKey(), entry.getValue());
        }
        return mm(s, strip);
    }

    public static Component mm(String s, TagResolver resolver) {
        return MiniMessage.miniMessage().deserialize(s, resolver);
    }


    public static Component mm(String s, boolean strip) {
        Component component = MiniMessage.miniMessage().deserialize(s);
        return strip ? strip(component) : component;
    }

    public static Component legacy(String message) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message);
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static String plain(String minimessage) {
        return PlainTextComponentSerializer.plainText().serialize(mm(minimessage));
    }

    public static Component strip(Component component) {
        if (component == null) return null;
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static Component parseTime(long duration, TimeUnit unit) {
        String s = String.format("&a%d &eчасов",
                unit.toHours(duration)
        );
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    public static Component error() {
        return Component.text("Произошла ошибка!", NamedTextColor.RED)
                .append(Component.text(" Обратитесь к администратуору", NamedTextColor.GRAY));
    }

}
