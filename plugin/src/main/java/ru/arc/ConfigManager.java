package ru.arc;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigManager {

    private static Map<String, Config> configMap = new ConcurrentHashMap<>();

    public static Config get(String name) {
        return configMap.get(name);
    }

    public static Config create(Path folder, String fileName, String configName) {
        Config config = new Config(folder, fileName);
        configMap.put(configName, config);
        return config;
    }

    public static void reloadAll() {
        configMap.forEach((s, config) -> config.load());
    }
}
