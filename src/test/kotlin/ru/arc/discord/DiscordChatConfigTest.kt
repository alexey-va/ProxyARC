package ru.arc.discord

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import ru.arc.chat.ExternalChatMeta
import ru.arc.chat.ExternalChatRenderer
import ru.arc.config.EmptyConfig
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.ConfigManager
import ru.arc.config.ProxyConfigs
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DiscordChatConfigTest : FreeSpec({
    afterEach { ConfigManager.clear() }

    "loads the tracked ItemsAdder Discord prefix" {
        val root = Files.createTempDirectory("discord-chat-config")
        val config = DiscordChatConfig.load(root).also(DiscordChatConfig::validate)

        config.minecraftFormat shouldBe
            "<white></white>󰼑 <dark_gray>| <gray>%player_name% <dark_gray>» <white>%message%"
        PlainTextComponentSerializer.plainText().serialize(
            config.minecraftMessage("GrocerMC", Component.text("123")),
        ) shouldBe "󰼑 | GrocerMC » 123"
        PlainTextComponentSerializer.plainText().serialize(
            config.minecraftReplyMessage("GrocerMC", "Alex", "старое", Component.text("123")),
        ) shouldBe "󰼑 | GrocerMC ← 123"
    }

    "renders Telegram placeholders in one pass" {
        val root = Files.createTempDirectory("discord-chat-token-safe")
        val config = DiscordChatConfig.load(root).also(DiscordChatConfig::validate)

        config.telegramMessage("%message%", "hello") shouldBe "%message% » hello"
    }

    "normal and reply messages retain the linked prefix and literal message" {
        val config = DiscordChatConfig.load(Files.createTempDirectory("discord-chat-style"))
        val renderer = ExternalChatRenderer(
            metaProvider = { CompletableFuture.completedFuture(ExternalChatMeta("&6[VIP]")) },
            configProvider = { EmptyConfig },
        )
        val text = "<red>literal</red>"
        val body = Component.text(text).append(Component.text(" link", NamedTextColor.AQUA))
        val normal = renderer.render(UUID.randomUUID(), "PlayerOne", body) { sender, message ->
            config.minecraftMessage("PlayerOne", message, sender)
        }.join()
        val reply = renderer.render(UUID.randomUUID(), "PlayerOne", body) { sender, message ->
            config.minecraftReplyMessage("PlayerOne", "Other", "<red>preview", message, sender)
        }.join()

        PlainTextComponentSerializer.plainText().serialize(normal) shouldBe
            "󰼑 | [VIP] PlayerOne » $text link"
        PlainTextComponentSerializer.plainText().serialize(reply) shouldBe
            "󰼑 | [VIP] PlayerOne ← $text link"
    }

    "rejects a format that can hide the message" {
        val root = Files.createTempDirectory("discord-chat-missing-message")
        ProxyConfigs.module(root, "discord-chat.yml").also { config ->
            config.setString("formats.minecraft", "<gray>%player_name%")
            config.saveStrict()
        }
        ConfigManager.clear()

        shouldThrow<IllegalArgumentException> {
            DiscordChatConfig.load(root).validate()
        }
    }
})
