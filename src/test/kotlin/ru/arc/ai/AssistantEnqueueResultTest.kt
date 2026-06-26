package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class AssistantEnqueueResultTest : FreeSpec({
    "AssistantEnqueueResult" - {
        "should mark reply as present" {
            AssistantEnqueueResult.reply("привет").hasReply shouldBe true
        }

        "should mark skip without reply" {
            AssistantEnqueueResult.skip(
                reason = SkipReason.MODEL_SKIP,
                raw = "пропускаю",
                triggerPlayer = "grocermc",
                triggerMessage = "скорен че как",
            ).hasReply shouldBe false
        }
    }
})
