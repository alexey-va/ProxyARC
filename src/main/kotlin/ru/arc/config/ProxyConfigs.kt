package ru.arc.config

import ru.arc.velocity.Velocity
import java.nio.file.Path

/** ProxyARC config paths: `config.yml` at data root, feature YAML under `modules/`. */
object ProxyConfigs {
    fun dataRoot(): Path = requireNotNull(Velocity.dataFolder) { "Velocity dataFolder is not set" }

    /** Main plugin config (data folder root). */
    fun main(): Config = ConfigManager.of(dataRoot(), "config.yml")

    /** Module YAML (`modules/name.yml`), falls back to legacy root file if present. */
    fun module(fileName: String): Config = ConfigManager.ofModule(dataRoot(), fileName)

    fun module(dataRoot: Path, fileName: String): Config = ConfigManager.ofModule(dataRoot, fileName)
}
