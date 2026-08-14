package ru.arc.velocity.listeners

import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.server.RegisteredServer
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class RestartKickListenerTest : FreeSpec({
    val listener = RestartKickListener()
    val player = mockk<Player>()
    val sourceServer = mockk<RegisteredServer>()
    val fallbackServer = mockk<RegisteredServer>()
    val plain = PlainTextComponentSerializer.plainText()

    "planned restart keeps redirect target and replaces Velocity wrapper" {
        val event =
            KickedFromServerEvent(
                player,
                sourceServer,
                Component.text("Сервер перезагружается!", TextColor.color(0xC42323)),
                false,
                KickedFromServerEvent.RedirectPlayer.create(
                    fallbackServer,
                    Component.text("Вы были кикнуты с сервера spawn: Сервер перезагружается!"),
                ),
            )

        listener.onKickedFromServer(event)

        val result = event.result as KickedFromServerEvent.RedirectPlayer
        val message = result.messageComponent.shouldNotBeNull()
        val rendered = plain.serialize(message)
        result.server shouldBe fallbackServer
        rendered shouldBe
            "⚠ Сервер перезапускается\n" +
            "Вы остались в сети на другом сервере.\n" +
            "Вернуться можно через несколько минут."
        rendered shouldNotContain "\\n"
        message.style().color() shouldBe null
        message.children().first().style().color() shouldBe TextColor.color(0xFF9F0F)
        message.children().first().hasDecoration(TextDecoration.BOLD) shouldBe true
        message.children()[2].style().color() shouldBe TextColor.color(0xE6FFF3)
        message.children()[4].style().color() shouldBe TextColor.color(0x969696)
        message.children()[5].style().color() shouldBe TextColor.color(0x92BED8)
    }

    "planned restart gets a standalone disconnect screen when no fallback exists" {
        val event =
            KickedFromServerEvent(
                player,
                sourceServer,
                Component.text("Сервер перезагружается. Зайдите через минуту."),
                false,
                KickedFromServerEvent.DisconnectPlayer.create(Component.text("old")),
            )

        listener.onKickedFromServer(event)

        val result = event.result as KickedFromServerEvent.DisconnectPlayer
        plain.serialize(result.reasonComponent) shouldBe
            "⚠ Сервер перезапускается\n" +
            "Мы уже запускаем сервер снова.\n" +
            "Зайдите снова через несколько минут."
    }

    "planned restart notification keeps the player connected" {
        val event =
            KickedFromServerEvent(
                player,
                sourceServer,
                Component.text("Сервер перезапускается."),
                true,
                KickedFromServerEvent.Notify.create(Component.text("old")),
            )

        listener.onKickedFromServer(event)

        val result = event.result as KickedFromServerEvent.Notify
        plain.serialize(result.messageComponent) shouldBe
            "⚠ Сервер перезапускается\n" +
            "Вы остались в сети на другом сервере.\n" +
            "Вернуться можно через несколько минут."
    }

    "ordinary kick result stays untouched" {
        val original = KickedFromServerEvent.DisconnectPlayer.create(Component.text("Вы заблокированы."))
        val event =
            KickedFromServerEvent(
                player,
                sourceServer,
                Component.text("Вы заблокированы."),
                false,
                original,
            )

        listener.onKickedFromServer(event)

        event.result shouldBe original
    }
})
