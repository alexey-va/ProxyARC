package ru.arc.ai.npc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import ru.arc.config.ConfigManager
import java.nio.file.Files

class NpcDialogueConfigTest : FreeSpec({
    afterTest { ConfigManager.clear() }

    "bundled Origin personas and prompts stay aligned" {
        val dataRoot = Files.createTempDirectory("proxyarc-npc-dialogue")
        val config = NpcDialogueConfig.load(dataRoot)

        config.personas.size shouldBe 13
        config.personas.keys shouldBe NpcDialogueConfig.PERSONA_IDS
        val host = checkNotNull(config.personas["arrival_host"])
        config.systemPrompt(host, "Alex") shouldContain "игрок Alex"
        config.systemPrompt(host, "Alex") shouldContain "Встречающий"
        config.systemPrompt(host, "Alex") shouldNotContain "%player_name%"
    }
})
