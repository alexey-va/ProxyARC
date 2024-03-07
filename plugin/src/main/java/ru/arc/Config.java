package ru.arc;

import lombok.SneakyThrows;
import net.kyori.adventure.text.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static ru.arc.Utils.mm;

public class Config {

    Map<String, Object> map;
    private final Path folder;
    private final String filePath;


    @SneakyThrows
    public Config(Path folder, String filePath) {
        this.folder = folder;
        this.filePath = filePath;

        copyDefaultConfig(filePath, folder, false);
        load();
    }

    public int integer(String path, int def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        return ((Number) o).intValue();
    }

    public double realNumber(String path, double def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        return ((Number) o).doubleValue();
    }

    public String string(String path, String def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        System.out.println(path+": "+o.toString());
        try {
            return (String) o;
        } catch (Exception e) {
            return o.toString();
        }
    }

    public Component component(String path, String... replacement) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, path);
            return mm(path);
        }
        String s;
        if (o instanceof String) s = (String) o;
        else s = o.toString();

        for(int i =0;i<replacement.length;i+=2){
            if(i+1 >= replacement.length) break;
            s=s.replace(replacement[i], replacement[i+1]);
        }

        return mm(s);
    }

    public List<String> stringList(String path) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, new ArrayList<String>());
            return new ArrayList<>();
        }
        return (List<String>) o;
    }

    private Object getValueForKeyPath(String keyPath) {
        String[] keyParts = keyPath.split("\\.");

        Map<String, Object> currentLevel = map;
        for (int i = 0; i < keyParts.length; i++) {
            String keyPart = keyParts[i];
            if (currentLevel.containsKey(keyPart)) {
                if (i == keyParts.length - 1) return currentLevel.get(keyPart);
                if (currentLevel.get(keyPart) instanceof Map) {
                    currentLevel = (Map<String, Object>) currentLevel.get(keyPart);
                }
            } else {
                // Key not found or not a mapping; handle accordingly
                System.out.println("Key not found: " + keyPath);
                return null;
            }
        }

        // Access the value at the final level
        return currentLevel.get(keyParts[keyParts.length - 1]);
    }

    public void injectDeepKey(String keyPath, Object value) {
        try {
            load();
            String[] keyParts = keyPath.split("\\.");

            Map<String, Object> currentLevel = map;
            for (int i = 0; i < keyParts.length - 1; i++) {
                currentLevel.putIfAbsent(keyParts[i], new LinkedHashMap<>());
                currentLevel = (Map<String, Object>) currentLevel.get(keyParts[i]);
            }

            // Inject the value at the final level
            currentLevel.put(keyParts[keyParts.length - 1], value);
            save();
        } catch (Exception e) {
            System.out.println("Could not inject key: " + keyPath + " with value: " + value);
            e.printStackTrace();
        }
    }

    @SneakyThrows
    public void load() {
        Yaml yaml = new Yaml();
        File configFile = folder.resolve(filePath).toFile();
        map = yaml.load(new FileInputStream(configFile));
        if (map == null) map = new HashMap<>();
        System.out.println("Loaded config: " + filePath);
    }

    public void save() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);
        String file = folder.resolve(filePath).toFile().getPath();
        try (var writer = new FileWriter(file)) {
            yaml.dump(map, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public static void copyDefaultConfig(String resource, Path folder, boolean replace) {
        try (var stream = Config.class.getClassLoader().getResourceAsStream(resource)) {
            Path path = folder.resolve(resource);
            if (Files.exists(path)) {
                if (!replace) {
                    System.out.println(resource + " already exists! Skipping...");
                    return;
                }
            }
            Files.createDirectories(path.getParent());
            if (stream == null) {
                Files.createFile(path);
            } else {
                Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
