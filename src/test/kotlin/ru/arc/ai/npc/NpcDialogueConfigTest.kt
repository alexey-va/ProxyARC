package ru.arc.ai.npc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import ru.arc.config.ConfigManager
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class NpcDialogueConfigTest : FreeSpec({
    afterTest { ConfigManager.clear() }

    "bundled config starts with NPC dialogue disabled and no personas" {
        val dataRoot = Files.createTempDirectory("proxyarc-npc-dialogue")
        val config = NpcDialogueConfig.load(dataRoot)

        config.enabled shouldBe false
        config.personas.shouldBeEmpty()
    }

    "runtime config may declare a scene-owned persona and prompt" {
        val dataRoot = Files.createTempDirectory("proxyarc-npc-dialogue")
        val moduleRoot = dataRoot.resolve("modules").createDirectories()
        val promptRoot = dataRoot.resolve("prompts/npc").createDirectories()
        moduleRoot.resolve("npc-dialogue.yml").writeText(
            """
            enabled: true
            defaults:
              model: openai/gpt-4o-mini
              max-tokens: 120
              temperature: 0.65
            personas:
              guide:
                display-name: Проводник
                prompt-file: prompts/npc/guide.txt
            """.trimIndent(),
        )
        promptRoot.resolve("common.txt").writeText("Ты говоришь с игроком %player_name%.")
        promptRoot.resolve("guide.txt").writeText("Ты Проводник.")

        val config = NpcDialogueConfig.load(dataRoot)
        val guide = checkNotNull(config.personas["guide"])

        config.enabled shouldBe true
        config.personas.keys shouldBe setOf("guide")
        config.systemPrompt(guide, "Alex") shouldContain "игроком Alex"
        config.systemPrompt(guide, "Alex") shouldContain "Проводник"
        config.systemPrompt(guide, "Alex") shouldNotContain "%player_name%"
    }
})
