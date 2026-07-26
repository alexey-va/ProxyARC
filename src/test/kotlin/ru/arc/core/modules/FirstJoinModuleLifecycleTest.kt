package ru.arc.core.modules

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import ru.arc.FirstJoinData
import ru.arc.velocity.Velocity
import java.nio.file.Files

class FirstJoinModuleLifecycleTest : FreeSpec({
    val dataPath = Files.createTempDirectory("proxyarc-first-join-test")

    afterTest {
        Velocity.firstJoinData = null
        Velocity.dataFolder = null
    }
    afterSpec {
        dataPath.toFile().deleteRecursively() shouldBe true
    }

    "failed load should not publish partially initialized data" {
        Files.writeString(dataPath.resolve("first_time_join.json"), "{broken")
        val previous = FirstJoinData(dataPath.resolve("previous.json"))
        Velocity.dataFolder = dataPath
        Velocity.firstJoinData = previous

        shouldThrow<Exception> {
            FirstJoinModule.init()
        }

        Velocity.firstJoinData shouldBeSameInstanceAs previous
    }
})
