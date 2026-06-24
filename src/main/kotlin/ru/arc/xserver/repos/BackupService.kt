package ru.arc.xserver.repos

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.apache.logging.log4j.LogManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class BackupService(
    private val id: String,
    private val folder: Path,
) {
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    fun saveBackup(map: Map<String, *>) {
        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss")
        val fileName = "${id}_backup_${now.format(formatter)}.json"
        try {
            if (!Files.exists(folder)) {
                Files.createDirectories(folder)
            }
            Files.writeString(
                folder.resolve(fileName),
                gson.toJson(map),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
            )
        } catch (e: Exception) {
            log.error("Failed to save backup", e)
        }
    }

    companion object {
        private val log = LogManager.getLogger(BackupService::class.java)
    }
}
