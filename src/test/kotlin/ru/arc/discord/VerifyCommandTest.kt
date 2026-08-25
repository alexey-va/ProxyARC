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
    val inviteUrl = "https://discord.gg/TJUXMGJD9q"

    "verification code is one isolated and actionable chat block" {
        val message = VerifyCommand.challengeMessage("ABCD1234", expiresInMinutes = 10, recovery = false, inviteUrl)
        val rendered = plain.serialize(message)

        rendered shouldBe
            "\n" +
            "  Привязка Discord-аккаунта\n" +
            "\n" +
            "  Код для Discord: ABCD1234\n" +
            "  Нажмите строку — код скопируется.\n" +
            "\n" +
            "  Открыть Discord RusCrafting\n" +
            "  В Discord введите /verify\n" +
            "  В поле code вставьте скопированный код.\n" +
            "\n" +
            "  Код действует 10 минут.\n"
        rendered shouldNotContain "\\n"
        rendered.lines().filter(String::isNotEmpty).all { it.startsWith("  ") } shouldBe true

        val code = message.descendants().filterIsInstance<TextComponent>().single { it.content() == "ABCD1234" }
        code.color() shouldBe TextColor.color(0xFFACD5)
        code.hasDecoration(TextDecoration.BOLD) shouldBe true
        code.clickEvent()?.action() shouldBe ClickEvent.Action.COPY_TO_CLIPBOARD
        code.clickEvent()?.value() shouldBe "ABCD1234"

        val codeRow = message.descendants().filterIsInstance<TextComponent>().single { it.content() == "Код для Discord: " }
        codeRow.clickEvent()?.action() shouldBe ClickEvent.Action.COPY_TO_CLIPBOARD
        codeRow.clickEvent()?.value() shouldBe "ABCD1234"

        val invite = message.descendants().filterIsInstance<TextComponent>().single { it.content() == "Открыть Discord RusCrafting" }
        invite.hasDecoration(TextDecoration.BOLD) shouldBe true
        invite.hasDecoration(TextDecoration.UNDERLINED) shouldBe true
        invite.clickEvent()?.action() shouldBe ClickEvent.Action.OPEN_URL
        invite.clickEvent()?.value() shouldBe inviteUrl
    }

    "recovery block names the different operation and keeps Russian minute forms" {
        plain.serialize(VerifyCommand.challengeMessage("ZXCV5678", 1, recovery = true, inviteUrl)) shouldBe
            "\n" +
            "  Перенос привязки Discord\n" +
            "\n" +
            "  Код для Discord: ZXCV5678\n" +
            "  Нажмите строку — код скопируется.\n" +
            "\n" +
            "  Открыть Discord RusCrafting\n" +
            "  В Discord введите /verify\n" +
            "  В поле code вставьте скопированный код.\n" +
            "\n" +
            "  Код действует 1 минуту.\n"
    }
})

private fun Component.descendants(): Sequence<Component> =
    sequence {
        yield(this@descendants)
        children().forEach { yieldAll(it.descendants()) }
    }
