package ru.arc.metrics

import com.velocitypowered.api.proxy.Player
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import ru.arc.telemetry.PlayerTelemetryEvent
import ru.arc.telemetry.PlayerTelemetryStore
import java.util.UUID

class ProxyPlayerTelemetryListenerTest :
    FreeSpec({
        "captures an existing proxy login event in the compact schema" {
            val playerId = UUID.randomUUID()
            val player = mockk<Player> {
                every { uniqueId } returns playerId
                every { username } returns "TestPlayer"
            }
            val captured = slot<PlayerTelemetryEvent>()
            val store = mockk<PlayerTelemetryStore> {
                every { offer(capture(captured)) } returns true
            }

            ProxyPlayerTelemetryListener(store, emptySet()).attach(player, resumed = false)

            val event = captured.captured
            event.schemaVersion shouldBe 2
            event.source shouldBe "proxy"
            event.event shouldBe "connection.login"
            event.playerId shouldBe playerId.toString()
            event.attributes shouldBe mapOf("censoredStart" to "false")
        }
    })
