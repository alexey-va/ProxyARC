package ru.arc.discord

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.ConfigManager
import ru.arc.config.ProxyConfigs
import java.nio.file.Files

class DiscordChatConfigTest : FreeSpec({
    afterEach { ConfigManager.clear() }

    "loads the tracked ItemsAdder Discord prefix" {
        val root = Files.createTempDirectory("discord-chat-config")
        val config = DiscordChatConfig.load(root).also(DiscordChatConfig::validate)

        config.minecraftFormat shouldBe
            "<white></white> <dark_gray>| <gray>%player_name% <dark_gray>» <white>%message%"
        PlainTextComponentSerializer.plainText().serialize(
            config.minecraftMessage("GrocerMC", Component.text("123")),
        ) shouldBe " | GrocerMC » 123"
        PlainTextComponentSerializer.plainText().serialize(
            config.minecraftReplyMessage("GrocerMC", "Alex", "старое", Component.text("123")),
        ) shouldBe " | GrocerMC ← 123"
    }

    "renders Telegram placeholders in one pass" {
        val root = Files.createTempDirectory("discord-chat-token-safe")
        val config = DiscordChatConfig.load(root).also(DiscordChatConfig::validate)

        config.telegramMessage("%message%", "hello") shouldBe "%message% » hello"
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
