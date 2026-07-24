package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class AssistantChatFormatTest : FreeSpec({
    "AssistantChatFormat" - {
        "should match CMI global shout format" {
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  display-name: "Скорен"
                  shout-prefix: "&6Ⓖ &7"
                  message-format: "%shout%%suffix% &7%name% &8» &f%message%"
                """.trimIndent(),
            )
            val config = Config(dir, "assistant.yml")
            val redstone = AssistantChatFormat.DEFAULT_SUFFIX

            AssistantChatFormat.inGameMessage(config, "привет") shouldBe
                "&6Ⓖ &7$redstone &7Скорен &8» &fпривет"
        }

        "should accept CMI placeholder names" {
            val format = "{shout}%luckperms_suffix% &7{displayName} &8» &f{message}"
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  display-name: "Скорен"
                  shout-prefix: "&6Ⓖ &7"
                """.trimIndent(),
            )
            val config = Config(dir, "assistant.yml")
            val redstone = AssistantChatFormat.DEFAULT_SUFFIX

            AssistantChatFormat.applyPlaceholders(format, config, "test") shouldBe
                "&6Ⓖ &7$redstone &7Скорен &8» &ftest"
        }

        "should split on blank line and clamp each part" {
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  max-message-length: 20
                """.trimIndent(),
            )
            val config = Config(dir, "assistant.yml")

            AssistantChatFormat.splitReplyParts(
                config,
                "первая строка\n\nвторая строка которую надо обрезать",
            ) shouldBe listOf("первая строка", "вторая строка")

            AssistantChatFormat.normalizeReply(
                config,
                "очень длинное сообщение которое точно не влезет в чат",
            ) shouldBe "очень длинное"
        }

        "should cap at two parts" {
            val config = Config(createTempDirectory(), "assistant.yml")
            AssistantChatFormat.splitReplyParts(
                config,
                "один\n\nдва\n\nтри\n\nчетыре",
            ) shouldBe listOf("один", "два")
        }

        "should chunk long single block into multiple messages" {
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("assistant.yml"),
                """
                chat:
                  max-message-length: 30
                """.trimIndent(),
            )
            val config = Config(dir, "assistant.yml")
            val long =
                "приходит мужик к врачу и говорит доктор у меня всё болит а врач отвечает ну ты даёшь"
            AssistantChatFormat.splitReplyParts(config, long) shouldBe
                listOf(
                    "приходит мужик к врачу и",
                    "говорит доктор у меня всё",
                )
        }

        "should explain skip reason for SKIP" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result = AssistantChatFormat.normalizeReplyDetail(config, "SKIP")
            result.hasText shouldBe false
            result.skipReason shouldBe "model said SKIP"
        }

        "should reject a meaningless numeric-only model reply" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result = AssistantChatFormat.normalizeReplyDetail(config, " 08")
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"
        }

        "should reject a mixed-script garbage token" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result = AssistantChatFormat.normalizeReplyDetail(config, "Sаковы")
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"

            AssistantChatFormat.normalizeReplyDetail(config, "rtp опять не работает").hasText shouldBe true
        }

        "should reject repeated-character garbage in an otherwise plausible reply" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result =
                AssistantChatFormat.normalizeReplyDetail(
                    config,
                    "LCP AAAAAA\n\nнельзя, вещи не сохраняются",
                )
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"
        }

        "should reject a punctuation-prefixed non-answer" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result = AssistantChatFormat.normalizeReplyDetail(config, ", сорян")
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"
        }

        "should reject an English meta-response instead of leaking it to Russian chat" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result =
                AssistantChatFormat.normalizeReplyDetail(
                    config,
                    "Wrote back the wrong answer and should try again.",
                )
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"

            AssistantChatFormat.normalizeReplyDetail(config, "Попробуй команду /rtp ещё раз").hasText shouldBe true
        }

        "should reject transcript-shaped history leakage" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result =
                AssistantChatFormat.normalizeReplyDetail(
                    config,
                    "а сохранить вещи можно?\n[20:45:02] QA_POLISH2 » а как это сделать?",
                )
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"
        }

        "should reject SentencePiece control-marker leakage with code-like tokens" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result =
                AssistantChatFormat.normalizeReplyDetail(
                    config,
                    "<｜begin_of_sentence｜>import;request;закрыть",
                )
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"
        }

        "should reject pipe-delimited transcript leakage even after a control marker" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result =
                AssistantChatFormat.normalizeReplyDetail(
                    config,
                    "<｜begin_of_sentence｜>| 21:59:59 | QA_FIX_CAP_724 » и вещи останутся?",
                )
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"
        }

        "should reject generated shell or code instead of sending it to chat" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result =
                AssistantChatFormat.normalizeReplyDetail(
                    config,
                    "#!/bin/bash\n# временный файл\nrm -f /tmp/skoren-answer",
                )
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"

            AssistantChatFormat.normalizeReplyDetail(config, "Введи /spawn и выбери сервер").hasText shouldBe true
        }

        "should reject letters from unrelated Unicode scripts" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result = AssistantChatFormat.normalizeReplyDetail(config, "8 氵 какой-то ответ")
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"

            AssistantChatFormat.normalizeReplyDetail(config, "TPS сейчас нормальный").hasText shouldBe true
        }

        "should reject a duplicated sentence or reply block" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result =
                AssistantChatFormat.normalizeReplyDetail(
                    config,
                    "а сохранить вещи при переходе можно?\n\nа сохранить вещи при переходе можно?",
                )
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"
        }

        "should reject a relative-time history prefix" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result =
                AssistantChatFormat.normalizeReplyDetail(
                    config,
                    "18 секунд назад попробуй перейти через /spawn",
                )
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"
        }

        "should reject bracketed internal context labels" {
            val config = Config(createTempDirectory(), "assistant.yml")
            val result =
                AssistantChatFormat.normalizeReplyDetail(
                    config,
                    "[продолжение с скореном] вижу вопрос, отвечаю.",
                )
            result.hasText shouldBe false
            result.skipReason shouldBe "low quality reply"
        }

        "should still accept legacy пропускаю" {
            AssistantChatFormat.isModelSkip("пропускаю") shouldBe true
        }

        "should treat SKIP with punctuation as skip" {
            AssistantChatFormat.isModelSkip("SKIP.") shouldBe true
            AssistantChatFormat.isModelSkip("skip!") shouldBe true
        }

        "should treat trailing SKIP line as skip" {
            AssistantChatFormat.isModelSkip("PM уже отправлен.\nSKIP") shouldBe true
        }

        "should treat trailing пропускаю line as skip" {
            AssistantChatFormat.isModelSkip("PM уже отправлен.\nпропускаю") shouldBe true
        }

        "should format discord like regular player chat" {
            val dir = createTempDirectory()
            Files.writeString(
                dir.resolve("config.yml"),
                "discord:\n  chat-pattern: \"**%player_name%** » %message%\"\n",
            )
            val main = Config(dir, "config.yml")

            AssistantChatFormat.discordMessage(main, "Скорен", "привет") shouldBe
                "**Скорен** » привет"
        }
    }
})
