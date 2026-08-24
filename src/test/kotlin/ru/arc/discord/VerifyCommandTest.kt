package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class VerifyCommandTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()

    "verification code is one isolated and actionable chat block" {
        val message = VerifyCommand.challengeMessage("ABCD1234", expiresInMinutes = 10, recovery = false)
        val rendered = plain.serialize(message)

        rendered shouldBe
            "\n" +
            "  Привязка Discord-аккаунта\n" +
            "  Код: ABCD1234\n" +
            "  Откройте сервер RusCrafting в Discord.\n" +
            "  Введите команду /verify\n" +
            "  В поле code вставьте код выше.\n" +
            "  Код действует 10 минут.\n"
        rendered shouldNotContain "\\n"
        rendered.lines().filter(String::isNotEmpty).all { it.startsWith("  ") } shouldBe true

        val code = message.descendants().filterIsInstance<TextComponent>().single { it.content() == "ABCD1234" }
        code.color() shouldBe TextColor.color(0xFFACD5)
        code.hasDecoration(TextDecoration.BOLD) shouldBe true
        code.clickEvent()?.action() shouldBe ClickEvent.Action.COPY_TO_CLIPBOARD
        code.clickEvent()?.value() shouldBe "ABCD1234"
    }

    "recovery block names the different operation and keeps Russian minute forms" {
        plain.serialize(VerifyCommand.challengeMessage("ZXCV5678", 1, recovery = true)) shouldBe
            "\n" +
            "  Перенос привязки Discord\n" +
            "  Код: ZXCV5678\n" +
            "  Откройте сервер RusCrafting в Discord.\n" +
            "  Введите команду /verify\n" +
            "  В поле code вставьте код выше.\n" +
            "  Код действует 1 минуту.\n"
    }
})

private fun Component.descendants(): Sequence<Component> =
    sequence {
        yield(this@descendants)
        children().forEach { yieldAll(it.descendants()) }
    }
