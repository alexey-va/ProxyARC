package ru.arc.ai.routing.router

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException

class RouteLogTest : FreeSpec({
    "RouteLog.describeError" - {
        "formats TimeoutException without message" {
            val wrapped =
                CompletionException(
                    TimeoutException(),
                )
            RouteLog.describeError(wrapped) shouldContain "TimeoutException"
        }

        "unwraps CompletionException" {
            RouteLog.describeError(
                CompletionException(IllegalStateException("LLM not ready")),
            ) shouldContain "LLM not ready"
        }
    }
})
