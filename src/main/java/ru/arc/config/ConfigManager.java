package ru.arc.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigManager {

    private static Map<String, Config> configMap = new ConcurrentHashMap<>();

    public static Config get(String name) {
        return configMap.get(name);
    }

    private static Config create(Path folder, String fileName, String configName) {
        Config config = new Config(folder, fileName);
        configMap.put(configName, config);
        return config;
    }

    public static Config of(Path folder, String filename) {
        String name = folder.toString() + "/" + filename;
        return getOrCreate(folder, filename, name);
    }

    private static Config getOrCreate(Path folder, String fileName, String configName) {
        Config config = configMap.get(configName);
        if (config == null) {
            config = create(folder, fileName, configName);
            configMap.put(configName, config);
        }
        return config;
    }

    public static void reloadAll() {
        configMap.forEach((s, config) -> config.load());
    }
}
