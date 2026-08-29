package ru.arc.discord

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.telegram.TelegramIdentityLink
import java.nio.file.Files
import java.util.UUID

class VerifyCommandTest : FreeSpec({
    val plain = PlainTextComponentSerializer.plainText()
    val inviteUrl = "https://discord.gg/TJUXMGJD9q"

    "already-linked messages identify the external account instead of repeating the Minecraft name" {
        telegramAccountLabel(
            TelegramIdentityLink(
                playerUuid = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                playerName = "Groceramse",
                telegramUserId = 123456789L,
                telegramUsername = "grocer_telegram",
                telegramDisplayName = "Alexey",
                linkedAt = 1L,
                updatedAt = 1L,
            ),
        ) shouldBe "@grocer_telegram"
        discordAccountLabel("grocer.discord", "987654321") shouldBe "@grocer.discord"
    }

    "external account labels have useful fallbacks when a username is unavailable" {
        telegramAccountLabel(
            TelegramIdentityLink(
                playerUuid = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                playerName = "Groceramse",
                telegramUserId = 123456789L,
                telegramUsername = null,
                telegramDisplayName = "Алексей",
                linkedAt = 1L,
                updatedAt = 1L,
            ),
        ) shouldBe "Алексей"
        discordAccountLabel(null, "987654321") shouldBe "Discord ID 987654321"
    }

    "verification code is one isolated and actionable chat block" {
        val messages = verificationConfig(Files.createTempDirectory("verify-command-message")).messages
        val message = messages.challenge("ABCD-EFGH", expiresInMinutes = 10, recovery = false)
        val rendered = plain.serialize(message)

        rendered shouldBe
            "\n" +
            "   Привязка Discord-аккаунта\n" +
            "\n" +
            "  Код для Discord: ABCD-EFGH\n" +
            "  Нажмите строку — команда /verify скопируется.\n" +
            "\n" +
            "  Открыть Discord RusCrafting\n" +
            "  В Discord вставьте команду и выполните её.\n" +
            "\n" +
            "  Код действует 10 минут.\n"
        rendered shouldNotContain "\\n"
        rendered.lines().filter(String::isNotEmpty).all { it.startsWith("  ") } shouldBe true

        val code = message.descendants().filterIsInstance<TextComponent>().single { it.content() == "ABCD-EFGH" }
        code.color() shouldBe TextColor.color(0xFFACD5)
        code.hasDecoration(TextDecoration.BOLD) shouldBe true
        code.hasDecoration(TextDecoration.UNDERLINED) shouldBe true
        val codeClickOwner =
            message.descendants().first {
                plain.serialize(it).contains("ABCD-EFGH") &&
                    it.clickEvent()?.action() == ClickEvent.Action.COPY_TO_CLIPBOARD
            }
        codeClickOwner.clickEvent()?.action() shouldBe ClickEvent.Action.COPY_TO_CLIPBOARD
        codeClickOwner.clickEvent()?.value() shouldBe "/verify ABCD-EFGH"

        val codeRow =
            message.descendants().first {
                plain.serialize(it).contains("Код для Discord: ABCD-EFGH") &&
                    it.clickEvent()?.action() == ClickEvent.Action.COPY_TO_CLIPBOARD
            }
        codeRow.clickEvent()?.action() shouldBe ClickEvent.Action.COPY_TO_CLIPBOARD
        codeRow.clickEvent()?.value() shouldBe "/verify ABCD-EFGH"

        val invite = message.descendants().filterIsInstance<TextComponent>().single { it.content() == "Открыть Discord RusCrafting" }
        invite.hasDecoration(TextDecoration.BOLD) shouldBe true
        invite.hasDecoration(TextDecoration.UNDERLINED) shouldBe true
        val inviteClickOwner =
            message.descendants().first {
                plain.serialize(it).contains("Открыть Discord RusCrafting") &&
                    it.clickEvent()?.action() == ClickEvent.Action.OPEN_URL
            }
        inviteClickOwner.clickEvent()?.action() shouldBe ClickEvent.Action.OPEN_URL
        inviteClickOwner.clickEvent()?.value() shouldBe inviteUrl
    }

    "recovery block names the different operation and keeps Russian minute forms" {
        val messages = verificationConfig(Files.createTempDirectory("verify-recovery-message")).messages
        plain.serialize(messages.challenge("ZXCV5678", 1, recovery = true)) shouldBe
            "\n" +
            "   Перенос привязки Discord\n" +
            "\n" +
            "  Код для Discord: ZXCV5678\n" +
            "  Нажмите строку — команда /verify скопируется.\n" +
            "\n" +
            "  Открыть Discord RusCrafting\n" +
            "  В Discord вставьте команду и выполните её.\n" +
            "\n" +
            "  Код действует 1 минуту.\n"
    }

    "compact Minecraft and Discord formats come from configuration" {
        val config =
            verificationConfig(
                Files.createTempDirectory("verify-custom-messages"),
                messageOverrides =
                    mapOf(
                        "messages.identity" to "Discord",
                        "messages.minecraft.identity" to "Discord",
                        "messages.minecraft.format" to "<identity> | <message>",
                        "messages.minecraft.status-linked" to "Игрок <player_name>",
                        "messages.discord.format" to "[%identity%] %message%",
                        "messages.discord.verified" to "OK %player_name%",
                    ),
            )

        plain.serialize(config.messages.minecraft("status-linked", "player_name" to "GrocerMC")) shouldBe
            "Discord | Игрок GrocerMC"
        config.messages.discord("verified", "player_name" to "GrocerMC") shouldBe
            "[Discord] OK GrocerMC"
    }

    "Discord Minecraft messages use the chat glyph without a dot and keep the community link clickable" {
        val messages = verificationConfig(Files.createTempDirectory("verify-discord-prefix")).messages
        val status = messages.minecraft("status-not-linked")
        val linked = messages.minecraft("already-linked", "player_name" to "GrocerMC")

        plain.serialize(status) shouldBe " Discord-аккаунт не связан."
        plain.serialize(status) shouldNotContain "•"
        plain.serialize(linked) shouldContain "Уже привязан: GrocerMC. [Discord]"
        val invite =
            linked.descendants().first {
                plain.serialize(it).contains("[Discord]") &&
                    it.clickEvent()?.action() == ClickEvent.Action.OPEN_URL
            }
        invite.clickEvent()?.value() shouldBe inviteUrl
    }

    "verification command resolves explicit platforms and direct aliases" {
        resolveVerificationInvocation(null, emptyList()) shouldBe
            VerificationInvocation(VerificationPlatform.DISCORD, emptyList())
        resolveVerificationInvocation(null, listOf("discord")) shouldBe
            VerificationInvocation(VerificationPlatform.DISCORD, emptyList())
        resolveVerificationInvocation(null, listOf("telegram", "status")) shouldBe
            VerificationInvocation(VerificationPlatform.TELEGRAM, listOf("status"))
        resolveVerificationInvocation(VerificationPlatform.DISCORD, listOf("recover")) shouldBe
            VerificationInvocation(VerificationPlatform.DISCORD, listOf("recover"))
        resolveVerificationInvocation(VerificationPlatform.TELEGRAM, emptyList()) shouldBe
            VerificationInvocation(VerificationPlatform.TELEGRAM, emptyList())
    }
})

private fun Component.descendants(): Sequence<Component> =
    sequence {
        yield(this@descendants)
        children().forEach { yieldAll(it.descendants()) }
    }
