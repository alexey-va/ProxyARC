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
            Tools.getTool("createissueticket").shouldNotBeNull()
            Tools.getTool("sendprivatemessage").shouldNotBeNull()
            Tools.getTool("sendglobalmessage").shouldNotBeNull()
            Tools.getTool("updateissueticket").shouldNotBeNull()
            Tools.getTool("listissuetickets").shouldNotBeNull()
            Tools.getTool("completebugsurvey").shouldNotBeNull()
        }
    }
})
