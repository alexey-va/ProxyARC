package ru.arc.ai.tickets

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class BugTicketDialogContextTest : FreeSpec({
    "enrichAppend includes player message and dialog" {
        val text =
            BugTicketDialogContext.enrichAppend(
                agentText = "игрок подтвердил фикс",
                dialog = "игрок: rtp не работает\nскорен (личка): какая команда?",
                triggerMessage = "не работает /rtp",
                reporter = "GrocerMC",
            )
        text shouldContain "игрок: не работает /rtp"
        text shouldContain "игрок подтвердил фикс"
        text shouldContain "скорен (личка)"
    }

    "enrichAppend falls back to agent text only" {
        val text =
            BugTicketDialogContext.enrichAppend(
                agentText = "только текст агента",
                dialog = "",
                triggerMessage = null,
                reporter = "GrocerMC",
            )
        text shouldContain "только текст агента"
        text shouldNotContain "**Диалог:**"
    }
})

class IssueTicketFormatTest : FreeSpec({
    "normalizeTitle strips player prefix and adds server" {
        val title =
            IssueTicketFormat.normalizeTitle(
                "[Игрок Quartz77] /kit starter дублирует",
                "Survival",
            )
        title shouldBe "/kit starter дублирует · мир биомов"
    }

    "buildDescription strips reporter boilerplate" {
        val desc =
            IssueTicketFormat.buildDescription(
                "Игрок NetherDiver сообщает, что /shop buy выдаёт алмаз бесплатно",
                "NetherDiver",
            )
        desc shouldBe "Суть: /shop buy выдаёт алмаз бесплатно"
    }

    "stripEmbeddedLocations removes duplicate server tail" {
        PlayerWorldNames.stripEmbeddedLocations("/shop buy на survival - Survival") shouldBe "/shop buy"
        PlayerWorldNames.stripEmbeddedLocations("/trade на classic · Спавн") shouldBe "/trade"
    }

    "normalizeTitle strips embedded location before world suffix" {
        IssueTicketFormat.normalizeTitle(
            "/shop buy на survival - Survival",
            "survival",
        ) shouldBe "/shop buy · мир биомов"
    }

    "normalizeTitle does not duplicate world suffix" {
        IssueTicketFormat.normalizeTitle(
            "/kit starter · мир биомов",
            "survival",
        ) shouldBe "/kit starter · мир биомов"
    }

    "markClosed via normalizeTitle" {
        IssueTicketFormat.normalizeTitle("GUI null", "спавн", closed = true) shouldBe
            "[Закрыт] GUI null · спавн"
    }

    "extractWorldSuffix reads canonical title tail" {
        IssueTicketFormat.extractWorldSuffix("GUI кланов null null · спавн") shouldBe "спавн"
    }

    "normalizeTitle closed uses server when title has no world yet" {
        IssueTicketFormat.normalizeTitle(
            "[Закрыт] GUI кланов null null",
            "спавн",
            closed = true,
        ) shouldBe "[Закрыт] GUI кланов null null · спавн"
    }
})
