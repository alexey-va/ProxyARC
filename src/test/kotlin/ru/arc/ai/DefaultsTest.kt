package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.string.shouldNotBeBlank

class DefaultsTest : FreeSpec({
    "Defaults" - {
        "should expose non-empty default system prompt" {
            Defaults.DEFAULT_SYSTEM.length shouldBeGreaterThan 50
            Defaults.DEFAULT_SYSTEM.shouldNotBeBlank()
        }
    }
})
