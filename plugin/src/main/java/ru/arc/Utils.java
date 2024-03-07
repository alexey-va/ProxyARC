package ru.arc;

import net.kyori.adventure.Adventure;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class Utils {

    public static String formatTime(long millis){
        long days = millis / (24 * 60 * 60 * 1000);
        long hours = (millis % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000);
        long minutes = (millis % (60 * 60 * 1000)) / (60 * 1000);

        return String.format(ConfigManager.get("config")
                .string("time-format", "%dд %dч %dм"), days, hours, minutes);
    }

    public static Component mm(String message){
        return MiniMessage.miniMessage().deserialize(message);
    }

    public static String plain(Component component){
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
