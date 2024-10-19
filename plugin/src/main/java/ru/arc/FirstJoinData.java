package ru.arc;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FirstJoinData {

    private final Path dbPath = CommonCore.folder.resolve("first_time_join.json");
    Map<String, Long> map;

    public boolean firstTimeJoin(String name) {
        return !map.containsKey(name);
    }

    public void markAsJoined(String name) {
        //System.out.println("Marking "+name);
        map.put(name, System.currentTimeMillis());
    }

    public Long getFirstJoinTime(String name) {
        return map.get(name);
    }

    @SneakyThrows
    public void load() {
        if (!Files.exists(dbPath)) {
            Files.createDirectories(dbPath.getParent());
            Files.createFile(dbPath);
        }

        Gson gson = new Gson();
        Type mapType = new TypeToken<ConcurrentHashMap<String, Long>>() {
        }.getType();
        Map<String, Long> loadedMap = gson.fromJson(new FileReader(dbPath.toFile()), mapType);
        if (loadedMap == null) {
            loadedMap = new ConcurrentHashMap<>();
        }
        map = loadedMap;
    }

    @SneakyThrows
    public void save() {
        if (!Files.exists(dbPath)) {
            Files.createDirectories(dbPath.getParent());
            Files.createFile(dbPath);
        }

        Gson gson = new Gson();
        try (FileWriter writer = new FileWriter(dbPath.toFile())) {
            //System.out.println("Writing to file");
            gson.toJson(map, writer);
        }
    }

}
