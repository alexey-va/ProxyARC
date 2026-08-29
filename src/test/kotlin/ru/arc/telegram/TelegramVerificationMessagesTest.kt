package ru.arc.telegram

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.ConfigManager
import java.nio.file.Files

class TelegramVerificationMessagesTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()

    afterEach { ConfigManager.clear() }

    "Telegram Minecraft messages use their aligned chat glyph without a dot" {
        val root = Files.createTempDirectory("telegram-verification-messages-")
        val messages = TelegramVerificationMessages(TelegramConfig.load(root))

        val status = messages.minecraft("status-not-linked")

        plain.serialize(status) shouldBe " Telegram-аккаунт не связан."
        plain.serialize(status) shouldNotContain "•"
    }

    "Telegram challenge is one actionable block with a clickable bot link" {
        val root = Files.createTempDirectory("telegram-verification-challenge-")
        val messages = TelegramVerificationMessages(TelegramConfig.load(root))

        val message = messages.challenge("ABCD-EFGH", expiresInMinutes = 10)

        plain.serialize(message) shouldBe
            "\n" +
            "   Привязка Telegram-аккаунта\n" +
            "\n" +
            "  Код для Telegram: ABCD-EFGH\n" +
            "  Нажмите строку — команда /verify скопируется.\n" +
            "\n" +
            "  Открыть Telegram-бот RusCrafting\n" +
            "  В личном чате отправьте /verify ABCD-EFGH.\n" +
            "\n" +
            "  Код действует 10 минут.\n"

        val code = message.descendants().filterIsInstance<TextComponent>().single { it.content() == "ABCD-EFGH" }
        code.hasDecoration(TextDecoration.UNDERLINED) shouldBe true
        val codeClickOwner =
            message.descendants().first {
                plain.serialize(it).contains("ABCD-EFGH") &&
                    it.clickEvent()?.action() == ClickEvent.Action.COPY_TO_CLIPBOARD
            }
        codeClickOwner.clickEvent()?.value() shouldBe "/verify ABCD-EFGH"

        val codeRow =
            message.descendants().first {
                plain.serialize(it).contains("Код для Telegram: ABCD-EFGH") &&
                    it.clickEvent()?.action() == ClickEvent.Action.COPY_TO_CLIPBOARD
            }
        codeRow.clickEvent()?.value() shouldBe "/verify ABCD-EFGH"

        val botLink =
            message.descendants().first {
                plain.serialize(it).contains("Открыть Telegram-бот RusCrafting") &&
                    it.clickEvent()?.action() == ClickEvent.Action.OPEN_URL
            }
        botLink.clickEvent()?.value() shouldBe "https://t.me/RusCrafting"
    }

    "already linked Telegram response keeps the bot link clickable" {
        val root = Files.createTempDirectory("telegram-verification-linked-")
        val messages = TelegramVerificationMessages(TelegramConfig.load(root))

        val linked = messages.minecraft("already-linked", "player_name" to "GrocerMC")

        plain.serialize(linked) shouldContain "Уже привязан: GrocerMC. [Telegram]"
        val botLink =
            linked.descendants().first {
                plain.serialize(it).contains("[Telegram]") &&
                    it.clickEvent()?.action() == ClickEvent.Action.OPEN_URL
            }
        botLink.clickEvent()?.value() shouldBe "https://t.me/RusCrafting"
    }

    "linked Telegram status stays compact for a maximum-length username" {
        val root = Files.createTempDirectory("telegram-verification-status-")
        val messages = TelegramVerificationMessages(TelegramConfig.load(root))

        plain.serialize(
            messages.minecraft(
                "status-linked",
                "telegram_username" to "@VeryLongTelegramUsername_12345",
                "player_name" to "VeryLongUser1234",
            ),
        ) shouldBe " Привязан: @VeryLongTelegramUsername_12345."
    }
})

private fun Component.descendants(): Sequence<Component> =
    sequence {
        yield(this@descendants)
        children().forEach { yieldAll(it.descendants()) }
    }
