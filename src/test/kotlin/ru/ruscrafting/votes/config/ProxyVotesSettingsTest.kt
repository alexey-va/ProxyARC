package ru.ruscrafting.votes.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class ProxyVotesSettingsTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "bundled ProxyARC vote ingress is loopback-only and opt-in" {
        val root = Files.createTempDirectory("proxy-votes-settings")
        val settings = ArcVotesSettings.load(root) { null }

        settings.http.enabled shouldBe false
        settings.http.bindAddress.isLoopbackAddress shouldBe true
        settings.http.port shouldBe 25826
        settings.sql shouldBe null
        settings.reward.enabled shouldBe false
        settings.enabledSources shouldBe emptySet()
    }
})
