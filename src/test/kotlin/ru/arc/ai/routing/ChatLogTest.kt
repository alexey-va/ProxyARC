package ru.arc.ai.routing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.observe.ChatLog

class ChatLogTest : FreeSpec({
    "ChatLog" - {
        "compacts to max lines" {
            val log = ChatLog(maxLines = 3)
            log.append("line1")
            log.append("line2")
            log.append("line3")
            log.append("line4")
            log.snapshot().shouldBe(listOf("line2", "line3", "line4"))
        }
    }
})
