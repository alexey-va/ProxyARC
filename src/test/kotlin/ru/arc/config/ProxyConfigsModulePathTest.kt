package ru.arc.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ProxyConfigsModulePathTest : FreeSpec({
    "ConfigManager.moduleYamlRelative" - {
        "should prefer modules/ for new install" {
            val dir = Files.createTempDirectory("proxyarc-cfg")
            try {
                ConfigManager.moduleYamlRelative(dir, "logging.yml") shouldBe "modules/logging.yml"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "should use legacy root file when modules/ missing" {
            val dir = Files.createTempDirectory("proxyarc-legacy")
            try {
                Files.writeString(dir.resolve("discord.yml"), "enabled: false\n")
                ConfigManager.moduleYamlRelative(dir, "discord.yml") shouldBe "discord.yml"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }
})
