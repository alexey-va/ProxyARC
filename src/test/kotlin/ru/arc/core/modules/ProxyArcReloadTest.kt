package ru.arc.core.modules

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly

class ProxyArcReloadTest : FreeSpec({
    "hot reload plan covers every safely restartable config-backed module in dependency order" {
        ProxyArcReload.moduleNames() shouldContainExactly
            listOf(
                "Logging",
                "Network",
                "Metrics",
                "Telegram",
                "ChannelSync",
                "Assistant",
                "ProxyOpsHttp",
            )
    }
})
