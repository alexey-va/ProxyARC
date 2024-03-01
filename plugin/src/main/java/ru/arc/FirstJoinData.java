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

@RequiredArgsConstructor
public class FirstJoinData {

    private final Path dbPath;
    Map<String, Long> map;

    public boolean firstTimeJoin(UUID uuid){
        return map.containsKey(uuid.toString());
    }

    public void markAsJoined(UUID uuid){
        map.put(uuid.toString(), System.currentTimeMillis());
    }

    @SneakyThrows
    public void load(){
        if(!Files.exists(dbPath)){
            Files.createDirectories(dbPath.getParent());
            Files.createFile(dbPath);
        }

        Gson gson = new Gson();
        Type mapType = new TypeToken<ConcurrentHashMap<String, Long>>(){}.getType();
        Map<String, Long> loadedMap = gson.fromJson(new FileReader(dbPath.toFile()), mapType);
        if(loadedMap == null){
            loadedMap = new ConcurrentHashMap<>();
        }
        map = loadedMap;
    }

    @SneakyThrows
    public void save(){
        if(!Files.exists(dbPath)){
            Files.createDirectories(dbPath.getParent());
            Files.createFile(dbPath);
        }

        Gson gson = new Gson();
        try(FileWriter writer = new FileWriter(dbPath.toFile())){
            gson.toJson(map, writer);
        }
    }

}
