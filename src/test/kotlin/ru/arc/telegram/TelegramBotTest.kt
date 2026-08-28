package ru.arc.telegram

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Chat
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.generics.BotSession
import ru.arc.core.TestTaskScheduler
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class TelegramBotTest : FreeSpec({
    "updates without a message are ignored" {
        val bot = TelegramBot(config = TestTelegramConfig(), scheduler = TestTaskScheduler())

        shouldNotThrowAny {
            bot.onUpdateReceived(Update())
        }
        bot.close()
    }

    "outbound requests use the managed scheduler and stop after close" {
        val scheduler = TestTaskScheduler()
        val executions = AtomicInteger()
        val bot =
            TelegramBot(
                config = TestTelegramConfig(),
                scheduler = scheduler,
                requestExecutor = { executions.incrementAndGet() },
            )

        bot.enqueue(SendMessage())
        executions.get() shouldBe 0
        scheduler.executeImmediate()
        executions.get() shouldBe 1

        bot.enqueue(SendMessage())
        bot.close()
        scheduler.executeImmediate()
        executions.get() shouldBe 1
    }

    "runtime stops the registered session and closes the bot" {
        val scheduler = TestTaskScheduler()
        val executions = AtomicInteger()
        val session =
            mockk<BotSession>(relaxed = true) {
                every { isRunning } returns true
            }
        val bot =
            TelegramBot(
                config = TestTelegramConfig(),
                scheduler = scheduler,
                requestExecutor = { executions.incrementAndGet() },
            )
        val runtime = TelegramRuntime { session }

        runtime.start(bot)
        runtime.close()
        runtime.close()
        bot.enqueue(SendMessage())
        scheduler.executeImmediate()

        verify(exactly = 1) { session.stop() }
        executions.get() shouldBe 0
    }

    "failed registration closes the rejected bot" {
        val scheduler = TestTaskScheduler()
        val executions = AtomicInteger()
        val bot =
            TelegramBot(
                config = TestTelegramConfig(),
                scheduler = scheduler,
                requestExecutor = { executions.incrementAndGet() },
            )
        val runtime =
            TelegramRuntime {
                throw IllegalStateException("registration failed")
            }

        runCatching { runtime.start(bot) }.isFailure shouldBe true

        bot.enqueue(SendMessage())
        scheduler.executeImmediate()
        executions.get() shouldBe 0
    }

    "starting a replacement stops the previous session" {
        val firstSession =
            mockk<BotSession>(relaxed = true) {
                every { isRunning } returns true
            }
        val secondSession = mockk<BotSession>(relaxed = true)
        val sessions = ArrayDeque(listOf(firstSession, secondSession))
        val runtime = TelegramRuntime { sessions.removeFirst() }
        val firstBot = TelegramBot(config = TestTelegramConfig(token = "first"), scheduler = TestTaskScheduler())
        val secondBot = TelegramBot(config = TestTelegramConfig(token = "second"), scheduler = TestTaskScheduler())

        runtime.start(firstBot)
        runtime.start(secondBot)

        verify(exactly = 1) { firstSession.stop() }
        runtime.close()
    }

    "general messages are mirrored to the discussion topic and information channel" {
        val scheduler = TestTaskScheduler()
        val requests = mutableListOf<SendMessage>()
        val bot =
            TelegramBot(
                config =
                    TestTelegramConfig(
                        generalDestination = TelegramDestination("-100100", 7),
                        informationDestination = TelegramDestination("-100200"),
                    ),
                scheduler = scheduler,
                requestExecutor = requests::add,
            )

        bot.sendGeneralMessage("Важная информация")
        scheduler.executeImmediate()

        requests.map { it.chatId to it.messageThreadId }.shouldContainExactly(
            "-100100" to 7,
            "-100200" to null,
        )
        requests.map(SendMessage::getText).shouldContainExactly("Важная информация", "Важная информация")
        bot.close()
    }

    "Telegram chat input relays once with unparsed player content" {
        val relay = RecordingTelegramRelay()
        val bot =
            TelegramBot(
                config =
                    TestTelegramConfig(
                        chatDestination = TelegramDestination("-100100", 7),
                        discordFormat = "**%sender%** » %message%",
                    ),
                scheduler = TestTaskScheduler(),
                inboundRelay = relay,
            )

        bot.onUpdateReceived(
            telegramUpdate(
                chatId = -100100,
                threadId = 7,
                username = "%message%",
                text = "<click:run_command:'/op me'>hello</click>",
            ),
        )

        relay.discordChat shouldBe "**%message%** » <click:run_command:'/op me'>hello</click>"
        PlainTextComponentSerializer.plainText().serialize(relay.minecraftChat) shouldBe
            "T %message% » <click:run_command:'/op me'>hello</click>"
        relay.generalCalls shouldBe 0
        bot.close()
    }

    "Telegram input outside the configured topic is ignored" {
        val relay = RecordingTelegramRelay()
        val bot =
            TelegramBot(
                config = TestTelegramConfig(chatDestination = TelegramDestination("-100100", 7)),
                scheduler = TestTaskScheduler(),
                inboundRelay = relay,
            )

        bot.onUpdateReceived(telegramUpdate(-100100, 8, "Alex", "hello"))

        relay.discordChat shouldBe null
        bot.close()
    }

    "fixed Telegram chat bridge parses entities and neutralizes forged Discord mentions" {
        val relay = RecordingTelegramRelay()
        val bot =
            TelegramBot(
                config =
                    TestTelegramConfig(
                        chatDestination = TelegramDestination("-100100", 7),
                        discordFormat = "**%sender%** » %message%",
                    ),
                scheduler = TestTaskScheduler(),
                inboundRelay = relay,
            )
        val rawMention = "<@1073279640912789595>"

        bot.onUpdateReceived(
            telegramUpdate(
                chatId = -100100,
                threadId = 7,
                username = "telegram_user",
                text = "@everyone $rawMention hello",
                entities = listOf(MessageEntity("bold", 33, 5)),
            ),
        )

        relay.discordChat shouldBe
            "**telegram_user** » ＠everyone \\$rawMention **hello**"
        relay.allowedDiscordUserMentionIds shouldBe emptySet()
        bot.close()
    }

    "Telegram general input relays only to Discord general" {
        val relay = RecordingTelegramRelay()
        val bot =
            TelegramBot(
                config = TestTelegramConfig(generalDestination = TelegramDestination("-100100", 8)),
                scheduler = TestTaskScheduler(),
                inboundRelay = relay,
            )

        bot.onUpdateReceived(telegramUpdate(-100100, 8, "Alex", "general"))

        relay.generalMessage shouldBe "**Alex** » general"
        relay.discordChat shouldBe null
        bot.close()
    }

    "bot-authored Telegram input is ignored" {
        val relay = RecordingTelegramRelay()
        val bot =
            TelegramBot(
                config = TestTelegramConfig(chatDestination = TelegramDestination("-100100", 7)),
                scheduler = TestTaskScheduler(),
                inboundRelay = relay,
            )

        bot.onUpdateReceived(telegramUpdate(-100100, 7, "RelayBot", "loop", isBot = true))

        relay.discordChat shouldBe null
        bot.close()
    }

    "private Telegram verify command links the authenticated sender id" {
        val scheduler = TestTaskScheduler()
        val requests = mutableListOf<SendMessage>()
        val identity = testIdentityService()
        val playerUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val challenge = identity.issueChallenge(playerUuid, "PlayerOne") as TelegramChallengeIssueResult.Issued
        val bot =
            TelegramBot(
                config = TestTelegramConfig(identityEnabled = true),
                scheduler = scheduler,
                requestExecutor = requests::add,
                identityService = identity,
            )

        bot.onUpdateReceived(
            telegramUpdate(
                chatId = 777,
                threadId = null,
                username = "player_tg",
                text = "/verify ${challenge.code}",
                userId = 777L,
                chatType = "private",
            ),
        )
        scheduler.executeImmediate()
        scheduler.executeImmediate()

        identity.findByTelegramUserId(777L)?.playerUuid shouldBe playerUuid
        requests.single().text shouldBe "Telegram привязан к Minecraft-аккаунту PlayerOne."
        bot.close()
    }

    "linked Telegram sender uses the canonical Minecraft name in the bridge" {
        val scheduler = TestTaskScheduler()
        val relay = RecordingTelegramRelay()
        val identity = testIdentityService()
        val playerUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val challenge = identity.issueChallenge(playerUuid, "PlayerOne") as TelegramChallengeIssueResult.Issued
        identity.completeChallenge(challenge.code, 777L, "player_tg", "Telegram Name")
        val bot =
            TelegramBot(
                config =
                    TestTelegramConfig(
                        chatDestination = TelegramDestination("-100100", 7),
                        identityEnabled = true,
                    ),
                scheduler = scheduler,
                inboundRelay = relay,
                identityService = identity,
            )

        bot.onUpdateReceived(
            telegramUpdate(
                chatId = -100100,
                threadId = 7,
                username = "changed_username",
                text = "hello",
                userId = 777L,
            ),
        )

        relay.discordChat shouldBe "**PlayerOne** » hello"
        identity.findByTelegramUserId(777L)?.telegramUsername shouldBe "changed_username"
        bot.close()
    }

    "outbound messages are split at Telegram's limit" {
        val parts = TelegramBot.splitMessage("x".repeat(4_097))

        parts.map(String::length).shouldContainExactly(4_096, 1)
    }
})

private class RecordingTelegramRelay : TelegramInboundRelay {
    var discordChat: String? = null
    var minecraftChat: Component = Component.empty()
    var generalCalls: Int = 0
    var generalMessage: String? = null
    var allowedDiscordUserMentionIds: Set<String> = emptySet()

    override fun relayChat(
        discordMessage: String,
        minecraftMessage: Component,
        allowedDiscordUserMentionIds: Set<String>,
    ) {
        discordChat = discordMessage
        minecraftChat = minecraftMessage
        this.allowedDiscordUserMentionIds = allowedDiscordUserMentionIds
    }

    override fun relayGeneral(
        discordMessage: String,
        allowedDiscordUserMentionIds: Set<String>,
    ) {
        generalCalls++
        generalMessage = discordMessage
        this.allowedDiscordUserMentionIds = allowedDiscordUserMentionIds
    }
}

private fun telegramUpdate(
    chatId: Long,
    threadId: Int?,
    username: String,
    text: String,
    isBot: Boolean = false,
    userId: Long = 10L,
    chatType: String = "supergroup",
    entities: List<MessageEntity> = emptyList(),
): Update =
    Update().also { update ->
        update.message =
            Message().also { message ->
                message.messageId = 42
                message.messageThreadId = threadId
                message.chat = Chat(chatId, chatType)
                message.from =
                    User(userId, "Telegram user", isBot).also { user ->
                        user.userName = username
                    }
                message.text = text
                message.entities = entities
            }
    }

private fun testIdentityService(): TelegramIdentityService {
    var challengeId = 0
    return TelegramIdentityService(
        store = TelegramIdentityStore(Files.createTempDirectory("telegram-bot-identity-")),
        config = TestTelegramConfig(identityEnabled = true),
        clock = { 1_000L },
        codeGenerator = { "ABCDEFGH" },
        idGenerator = { "challenge-${++challengeId}" },
    )
}
