package ru.arc.ai.routing.survey

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.ingress.InboundMeta

class BugSurveySessionStoreTest : FreeSpec({
    beforeEach { BugSurveySessionStore.clear() }
    afterSpec { BugSurveySessionStore.clear() }

    "hasRecentGlobalAsk" - {
        BugSurveySessionStore.openOrTouch("yarostuf")
        val question = "у кого ещё в мире биомов сбрасывается изучение?"
        BugSurveySessionStore.markAwaitingGlobalResponses("yarostuf", question)

        BugSurveySessionStore.hasRecentGlobalAsk("yarostuf", question, 300_000L) shouldBe true
        BugSurveySessionStore.hasRecentGlobalAsk("yarostuf", "другой вопрос", 300_000L) shouldBe false
    }

    "global inquiry accepts explicit confirmations but not substrings inside ordinary chat" - {
        BugSurveySessionStore.openOrTouch("reporter")
        BugSurveySessionStore.markAwaitingGlobalResponses("reporter", "у кого ещё не работает?")
        val meta =
            InboundMeta(
                directedAtBot = false,
                replyToBot = false,
                continuationWithBot = false,
                secondsSinceBot = null,
                replyToPlayer = null,
            )

        BugSurveySessionStore
            .resolveSession("witness", "да, у меня тоже", meta, 300_000L)
            .shouldNotBeNull()
        BugSurveySessionStore
            .resolveSession("seller", "продам ключ", meta, 300_000L)
            .shouldBeNull()
    }
})
