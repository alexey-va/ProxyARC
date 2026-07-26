package ru.arc.ai.llm

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class LogPreviewTest : FreeSpec({
    "short text is normalized to one log line" {
        LogPreview.of("one\r\ntwo\nthree") shouldBe "one\\ntwo\\nthree"
    }

    "long untrusted text is bounded and fingerprinted" {
        val raw = "secret-ish-model-output\n".repeat(100)
        val preview = LogPreview.of(raw, maxChars = 64)

        (preview.length < 120) shouldBe true
        preview shouldContain "…[len=2500 sha256="
        preview shouldNotContain "\n"
    }

    "null is explicit" {
        LogPreview.of(null) shouldBe "<null>"
    }
})
