package ru.arc.xserver.repos;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor @Log4j2
public class BackupService {

    final String id;
    final Path folder;
    Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    public void saveBackup(Map<String, ?> map) {

        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");
        String fileName = id + "_backup_" + now.format(formatter) + ".json";
        try {
            if (!Files.exists(folder)) Files.createDirectories(folder);
            Files.writeString(folder.resolve(fileName), gson.toJson(map),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
