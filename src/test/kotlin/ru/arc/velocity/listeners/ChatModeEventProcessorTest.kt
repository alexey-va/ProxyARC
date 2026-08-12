package ru.arc.velocity.listeners

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent
import com.velocitypowered.api.proxy.Player
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.chat.ChatMode
import java.util.UUID

class ChatModeEventProcessorTest : FreeSpec({
    "proxy chat mode handler runs before other Velocity chat interceptors" {
        val subscription =
            ChatListener::class.java
                .getDeclaredMethod("onChatMessage", PlayerChatEvent::class.java)
                .getAnnotation(Subscribe::class.java)

        subscription.order shouldBe PostOrder.CUSTOM
        subscription.priority shouldBe Short.MAX_VALUE
    }

    "global mode exposes a logical prefix without replacing the Velocity message" {
        val playerId = UUID.randomUUID()
        val event = PlayerChatEvent(player(playerId), "Привет")
        val processor = ChatModeEventProcessor { ChatMode.GLOBAL }

        val outcome = processor.apply(event)

        outcome.effectiveMessage shouldBe "!Привет"
        outcome.logicalPrefixAdded shouldBe true
        outcome.globalBridgeMessage shouldBe "Привет"
        event.result.message.isEmpty shouldBe true
    }

    "global mode preserves a manually supplied prefix" {
        val playerId = UUID.randomUUID()
        val event = PlayerChatEvent(player(playerId), "!Привет")
        val processor = ChatModeEventProcessor { ChatMode.GLOBAL }

        val outcome = processor.apply(event)

        outcome.effectiveMessage shouldBe "!Привет"
        outcome.logicalPrefixAdded shouldBe false
        outcome.globalBridgeMessage shouldBe "Привет"
        event.result.message.isEmpty shouldBe true
    }

    "local mode leaves an unprefixed message local" {
        val playerId = UUID.randomUUID()
        val event = PlayerChatEvent(player(playerId), "Привет")
        val processor = ChatModeEventProcessor { ChatMode.LOCAL }

        val outcome = processor.apply(event)

        outcome.effectiveMessage shouldBe "Привет"
        outcome.logicalPrefixAdded shouldBe false
        outcome.globalBridgeMessage shouldBe null
        event.result.message.isEmpty shouldBe true
    }

    "effective message follows an earlier Velocity replacement" {
        val event = PlayerChatEvent(player(UUID.randomUUID()), "Привет")
        event.setResult(PlayerChatEvent.ChatResult.message("!Привет"))

        ChatModeEventProcessor.effectiveMessage(event) shouldBe "!Привет"
    }
})

private fun player(playerId: UUID): Player =
    mockk(relaxed = true) {
        every { uniqueId } returns playerId
    }
