package ru.arc.ai.tickets

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class PlayerWorldNamesTest : FreeSpec({
    "displayProxyOrWorld maps backend ids to player worlds" {
        PlayerWorldNames.displayProxyOrWorld("classic") shouldBe "спавн"
        PlayerWorldNames.displayProxyOrWorld("classic_survival") shouldBe "мир биомов"
        PlayerWorldNames.displayProxyOrWorld("survival") shouldBe "мир биомов"
        PlayerWorldNames.displayProxyOrWorld("parkour") shouldBe "паркур"
        PlayerWorldNames.displayProxyOrWorld("vanilla") shouldBe "ванильный мир"
        PlayerWorldNames.displayProxyOrWorld("mining") shouldBe "мир майнинга"
        PlayerWorldNames.displayProxyOrWorld("em_the_cave") shouldBe "данжи"
    }

    "resolveDisplay prefers message world over proxy id" {
        PlayerWorldNames.resolveDisplay(
            proxyOrHint = "classic_survival",
            messageText = "/trade не работает в ванильном мире",
        ) shouldBe "ванильный мир"
    }

    "inferFromText detects player vocabulary" {
        PlayerWorldNames.inferFromText("лагает на спавне") shouldBe "спавн"
        PlayerWorldNames.inferFromText("rtp в мир биомов") shouldBe "мир биомов"
    }
})
