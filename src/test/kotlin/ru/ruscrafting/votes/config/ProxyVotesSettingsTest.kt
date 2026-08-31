package ru.ruscrafting.votes.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

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
        settings.reward.pollIntervalSeconds shouldBe 5L
        settings.reward.standard.amount.compareTo(java.math.BigDecimal("1000")) shouldBe 0
        settings.reward.premium.amount.compareTo(java.math.BigDecimal("3")) shouldBe 0
        settings.reward.premium.currencyId shouldBe "tokens"
        settings.presentations.getValue(MonitoringSource.HOTMC).voteUrl shouldBe
            URI("https://hotmc.ru/vote-242482")
        settings.presentations.getValue(MonitoringSource.MONITORING_MINECRAFT).voteUrl shouldBe
            URI("https://monitoringminecraft.com/vote/43/")
        settings.enabledSources shouldBe emptySet()
    }

    "legacy reward amount is preserved without silently enabling premium rewards" {
        val root = Files.createTempDirectory("proxy-votes-legacy-settings")
        root.resolve("modules").createDirectories()
        root.resolve("modules/votes.yml").writeText(
            """
            mysql:
              enabled: true
            reward:
              enabled: true
              amount: 100.00
              currency-label: монет
            """.trimIndent(),
        )
        ArcVotesSettings.mergeDefaults(root)

        val settings = ArcVotesSettings.load(root) { "test-secret" }

        settings.reward.standard.amount.compareTo(java.math.BigDecimal("100.00")) shouldBe 0
        settings.reward.premium.enabled shouldBe false
    }

    "malformed legacy reward amount fails before bundled rewards are merged" {
        val root = Files.createTempDirectory("proxy-votes-malformed-legacy-settings")
        root.resolve("modules").createDirectories()
        root.resolve("modules/votes.yml").writeText(
            """
            reward:
              enabled: true
              amount: nope
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            ArcVotesSettings.mergeDefaults(root)
        }.message shouldBe "Legacy reward.amount must be a decimal"
    }

    "missing legacy reward amount fails before bundled rewards are merged" {
        val root = Files.createTempDirectory("proxy-votes-missing-legacy-settings")
        root.resolve("modules").createDirectories()
        root.resolve("modules/votes.yml").writeText(
            """
            reward:
              enabled: true
              currency-label: монет
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            ArcVotesSettings.mergeDefaults(root)
        }.message shouldBe "Legacy reward.amount is required"
    }
})
