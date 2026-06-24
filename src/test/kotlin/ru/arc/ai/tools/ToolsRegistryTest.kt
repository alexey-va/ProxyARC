package ru.arc.ai.tools

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull

class ToolsRegistryTest : FreeSpec({
    "Tools" - {
        "should register default tools" {
            Tools.getAllTools().shouldNotBeEmpty()
            Tools.getTool("leavefortime").shouldNotBeNull()
            Tools.getTool("getbaltop").shouldNotBeNull()
            Tools.getTool("getplayerinfo").shouldNotBeNull()
        }
    }
})
