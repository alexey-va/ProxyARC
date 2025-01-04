package ru.arc.config;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import ru.arc.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static ru.arc.Utils.mm;

@Slf4j
@SuppressWarnings("unchecked")
public class Config {

    Map<String, Object> map;
    private final Path folder;
    private final String filePath;


    @SneakyThrows
    Config(Path folder, String filePath) {
        this.folder = folder;
        this.filePath = filePath;

        copyDefaultConfig(filePath, folder, false);
        load();
    }

    public Integer integer(String path, int def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        return Integer.valueOf(((Number) o).intValue());
    }

    public Boolean bool(String path, boolean def) {
        Object o = getValueForKeyPath(path);
        Boolean def0 = Boolean.valueOf(def);
        if (o == null) {
            injectDeepKey(path, def0);
            return def0;
        }
        return (Boolean) o;
    }

    public Double real(String path, double def) {
        Object o = getValueForKeyPath(path);
        Double def0 = Double.valueOf(def);
        if (o == null) {
            injectDeepKey(path, def);
            return def0;
        }
        return Double.valueOf(((Number) o).doubleValue());
    }

    public String string(String path, String def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
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
            return mm(path, true);
        }
        String s;
        if (o instanceof String) s = (String) o;
        else s = o.toString();

        for (int i = 0; i < replacement.length; i += 2) {
            if (i + 1 >= replacement.length) break;
            s = s.replace(replacement[i], replacement[i + 1]);
        }

        return mm(s, true);
    }

    public Component componentDef(String path, String def, TagResolver tagResolver) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return Utils.strip(mm(def, tagResolver));
        }
        String s;
        if (o instanceof String) s = (String) o;
        else s = o.toString();

        return Utils.strip(mm(s, tagResolver));
    }

    public Component componentDef(String path, String def, String... replacers) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return mm(def, true, replacers);
        }
        String s;
        if (o instanceof String) s = (String) o;
        else s = o.toString();

        return mm(s, true, replacers);
    }

    public Component component(String path, TagResolver tagResolver) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, path);
            return Utils.strip(mm(path));
        }
        String s;
        if (o instanceof String) s = (String) o;
        else s = o.toString();

        return Utils.strip(mm(s, tagResolver));
    }

    public List<Component> componentList(String path, String... replacement) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, List.of(path));
            return List.of(Utils.strip(mm(path)));
        }

        List<Component> list = new ArrayList<>();
        if (o instanceof String s) {
            for (int i = 0; i < replacement.length; i += 2) {
                if (i + 1 >= replacement.length) break;
                s = s.replace(replacement[i], replacement[i + 1]);
            }
            list.add(mm(s, true));
        } else if (o instanceof List l) {
            for (Object obj : l) {
                if (obj instanceof String s1) {
                    for (int i = 0; i < replacement.length; i += 2) {
                        if (i + 1 >= replacement.length) break;
                        s1 = s1.replace(replacement[i], replacement[i + 1]);
                    }
                    list.add(mm(s1, true));
                }
            }
        }
        return list;
    }

    public List<Component> componentListDef(String path, List<String> def, String... replacement) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return componentList(path, replacement);
        }

        List<Component> list = new ArrayList<>();
        if (o instanceof String s) {
            for (int i = 0; i < replacement.length; i += 2) {
                if (i + 1 >= replacement.length) break;
                s = s.replace(replacement[i], replacement[i + 1]);
            }
            list.add(mm(s, true));
        } else if (o instanceof List l) {
            for (Object obj : l) {
                if (obj instanceof String s1) {
                    for (int i = 0; i < replacement.length; i += 2) {
                        if (i + 1 >= replacement.length) break;
                        s1 = s1.replace(replacement[i], replacement[i + 1]);
                    }
                    list.add(mm(s1, true));
                }
            }
        }
        return list;
    }

    public List<String> stringList(String path) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, new ArrayList<String>());
            return new ArrayList<>();
        }
        if (o instanceof String s) {
            return List.of(s);
        }
        return (List<String>) o;
    }

    public List<String> stringList(String path, List<String> def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        if (o instanceof String s) {
            return List.of(s);
        }
        return (List<String>) o;
    }

    public <T> Map<String, T> map(String path) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, new LinkedHashMap<String, T>());
            return new LinkedHashMap<>();
        }
        for (Object key : ((Map<Object, Object>) o).keySet()) {
            if (key instanceof String) continue;
            // if key us not string replace it with string
            String stringKey = key.toString();
            Object value = ((Map<Object, Object>) o).get(key);
            ((Map<Object, Object>) o).remove(key);
            ((Map<Object, Object>) o).put(stringKey, value);
            injectDeepKey(path, o);
        }
        return (Map<String, T>) o;
    }

    public <T> Map<String, T> map(String path, Map<String, T> def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        for (Object key : ((Map<Object, Object>) o).keySet()) {
            if (key instanceof String) continue;
            // if key us not string replace it with string
            String stringKey = key.toString();
            Object value = ((Map<Object, Object>) o).get(key);
            ((Map<Object, Object>) o).remove(key);
            ((Map<Object, Object>) o).put(stringKey, value);
            injectDeepKey(path, o);
        }
        return (Map<String, T>) o;
    }

    public <T> List<T> list(String path) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, new ArrayList<>());
            return new ArrayList<>();
        }
        return (List<T>) o;
    }

    public <T> List<T> list(String path, List<T> def) {
        Object o = getValueForKeyPath(path);
        if (o == null) {
            injectDeepKey(path, def);
            return def;
        }
        return (List<T>) o;
    }

    public List<String> keys(String path) {
        Map<String, Object> o = map(path, Map.of());
        if (o == null) return new ArrayList<>();
        return new ArrayList<>(o.keySet());
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
            System.out.println("Injecting key: " + keyPath + " with value: " + value);
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
            log.error("Could not inject key: {}", keyPath);
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
            log.error("Could not save config: {}", file);
        }
    }


    public static void copyDefaultConfig(String resource, Path folder, boolean replace) {
        try (var stream = Config.class.getClassLoader().getResourceAsStream(resource)) {
            Path path = folder.resolve(resource);
            if (Files.exists(path)) {
                if (!replace) {
                    //System.out.println(resource + " already exists! Skipping...");
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
            log.error("Could not copy default config: {}", resource);
        }
    }


    public String string(String s) {
        return string(s, s);
    }

    public void addToList(String path, Object value) {
        List<Object> list = list(path);
        list.add(value);
        injectDeepKey(path, list);
    }

    public boolean exists(String s) {
        return getValueForKeyPath(s) != null;
    }

    public Long longValue(String s, long def) {
        Object o = getValueForKeyPath(s);
        Long def0 = Long.valueOf(def);
        if (o == null) {
            injectDeepKey(s, def0);
            return def0;
        }
        return Long.valueOf(((Number) o).longValue());
    }
}
