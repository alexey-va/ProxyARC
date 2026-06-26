package ru.arc.core.modules

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldNotThrowAny
import ru.arc.velocity.Velocity

class SaveModuleReloadTest : FreeSpec({
    "SaveModule" - {
        "should survive reload without RejectedExecutionException" {
            Velocity.firstJoinData = null
            shouldNotThrowAny {
                SaveModule.init()
                SaveModule.reload()
                SaveModule.shutdown()
            }
        }
    }
})
