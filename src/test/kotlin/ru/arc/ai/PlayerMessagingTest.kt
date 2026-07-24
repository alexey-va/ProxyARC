package ru.arc.ai

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PlayerMessagingTest :
    StringSpec({
        "isPrivateMessageAccepted treats sent and offline as ok" {
            PlayerMessaging.isPrivateMessageAccepted(mapOf("status" to "sent")) shouldBe true
            PlayerMessaging.isPrivateMessageAccepted(mapOf("status" to "offline")) shouldBe true
            PlayerMessaging.isPrivateMessageAccepted(mapOf("status" to "error")) shouldBe false
        }
    })
