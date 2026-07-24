package ru.arc.ai.routing.router

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.ai.routing.context.RouterContext
import ru.arc.ai.routing.ingress.InboundMessage
import ru.arc.ai.routing.ingress.InboundMeta
import ru.arc.ai.routing.survey.BugSurveySession

class RoutePrefilterTest : FreeSpec({
    val config =
        RouterConfig(
            enabled = true,
            model = "test",
            fallbackModel = "test",
            temperature = 0.0,
            maxTokens = 256,
            maxContextLines = 8,
            maxRouteHistory = 3,
            continuationWindowSec = 90,
            observeFormat = "[%time%] %player% » %message%",
            timeoutSec = 15,
            logSkipAtDebug = true,
            logRouteInfo = true,
            enabledIntents = setOf(RouteIntent.CHAT, RouteIntent.BUG),
            recentOpenTickets = 1,
            prefilterEnabled = true,
        )

    "undirected ordinary chat skips without LLM" {
        val result = RoutePrefilter.classify(context("кто продаст ключ"), config)
        result?.intent shouldBe RouteIntent.SKIP
        result?.reason shouldBe "prefilter:undirected_non_bug"
    }

    "clear bug routes without LLM or global prefix" {
        val result = RoutePrefilter.classify(context("rtp не работает"), config)
        result?.intent shouldBe RouteIntent.BUG
        result?.reason shouldBe "prefilter:clear_bug"
    }

    "directed Cyrillic rtp failure outranks chat" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "скорен а у меня ртп молчит",
                    directedAtBot = true,
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.BUG
        result?.reason shouldBe "prefilter:clear_bug"
    }

    "representative real bug corpus routes locally without LLM" {
        listOf(
            "rtp не работает",
            "крашит клиент когда захожу в энд",
            "дюпается алмаз если так делать",
            "на survival тпс упал до 5, не норм",
            "после рестарта /rtp просто молчит и ниче",
            "/ah sell съедает предмет",
            "vote ключ не приходит",
            "сф быстрых машин сбрасывает изучение",
        ).forEach { text ->
            withClue(text) {
                val result = RoutePrefilter.classify(context(text), config)
                result?.intent shouldBe RouteIntent.BUG
                result?.reason shouldBe "prefilter:clear_bug"
                result?.model shouldBe "prefilter"
            }
        }
    }

    "ordinary stem collisions stay silent without LLM or bug intake" {
        listOf(
            "не покупай ключ у него",
            "не продавай ему мембраны",
            "не открывай сундук без меня",
            "бесплатно отдам ключ",
            "двойной прыжок прикольный",
            "дублирую сообщение",
            "сломал кирку",
            "куда ты пропал",
            "это была ошибка",
            "съедобный гриб",
            "какие баги сейчас есть?",
            "баги уже пофиксили?",
        ).forEach { text ->
            withClue(text) {
                val result = RoutePrefilter.classify(context(text), config)
                result?.intent shouldBe RouteIntent.SKIP
                result?.reason shouldBe "prefilter:undirected_non_bug"
                result?.model shouldBe "prefilter"
            }
        }
    }

    "explicit Скорен mention with global prefix routes chat" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "скорен ты тут",
                    raw = "!скорен ты тут",
                    directedAtBot = true,
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.CHAT
    }

    "explicit Скорен mention without global prefix routes chat" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "скорен ты тут",
                    directedAtBot = true,
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.CHAT
        result?.reason shouldBe "prefilter:directed_chat"
    }

    "direct acknowledgement stays silent even with global prefix" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "скорен, спасибо",
                    raw = "!скорен, спасибо",
                    directedAtBot = true,
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.SKIP
        result?.reason shouldBe "prefilter:directed_ack"
    }

    "external problem addressed to Скорен stays chat rather than bug intake" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "скорен, дискорд не работает",
                    raw = "!скорен, дискорд не работает",
                    directedAtBot = true,
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.CHAT
        result?.reason shouldBe "prefilter:directed_chat"
    }

    "undirected external problem stays silent without LLM" {
        val result = RoutePrefilter.classify(context("у меня интернет лагает"), config)
        result?.intent shouldBe RouteIntent.SKIP
        result?.reason shouldBe "prefilter:undirected_non_bug"
        result?.model shouldBe "prefilter"
    }

    "meaningful continuation routes directly to chat without router LLM" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "а почему так",
                    raw = "!а почему так",
                    continuationWithBot = true,
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.CHAT
        result?.reason shouldBe "prefilter:continuation_chat"
    }

    "meaningful continuation does not require another global prefix" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "как попасть на выживание",
                    continuationWithBot = true,
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.CHAT
        result?.reason shouldBe "prefilter:continuation_chat"
    }

    "low value continuation skips without LLM" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "пон",
                    raw = "!пон",
                    continuationWithBot = true,
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.SKIP
        result?.reason shouldBe "prefilter:low_value_continuation"
    }

    "conversation cap silences an implicit third turn but explicit Скорен reopens it" {
        val capped =
            RoutePrefilter.classify(
                context(
                    text = "а почему",
                    raw = "!а почему",
                    continuationWithBot = true,
                    botRepliesInThread = 2,
                ),
                config,
            )
        capped?.intent shouldBe RouteIntent.SKIP
        capped?.reason shouldBe "prefilter:conversation_cap"

        val explicit =
            RoutePrefilter.classify(
                context(
                    text = "скорен а почему",
                    raw = "!скорен а почему",
                    directedAtBot = true,
                    continuationWithBot = true,
                    botRepliesInThread = 2,
                ),
                config,
            )
        explicit?.intent shouldBe RouteIntent.CHAT
        explicit?.reason shouldBe "prefilter:directed_chat"
    }

    "player opt out is always silent" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "скорен не пиши мне",
                    raw = "!скорен не пиши мне",
                    directedAtBot = true,
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.SKIP
        result?.reason shouldBe "prefilter:opt_out"
    }

    "survey witness confirmation reaches bug intake without LLM" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "да, у меня тоже",
                    player = "Witness",
                    activeBugSurvey = survey(primary = "Reporter"),
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.BUG
        result?.reason shouldBe "prefilter:survey_witness"
    }

    "explicit off topic chat during survey stays chat" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "скорен а ты видел новый спавн?",
                    raw = "!скорен а ты видел новый спавн?",
                    directedAtBot = true,
                    continuationWithBot = true,
                    activeBugSurvey = survey(primary = "Tester"),
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.CHAT
        result?.reason shouldBe "prefilter:survey_direct_chat"
    }

    "survey details outrank explicit assistant chat" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "скорен, после /rtp кидает назад",
                    raw = "!скорен, после /rtp кидает назад",
                    directedAtBot = true,
                    continuationWithBot = true,
                    activeBugSurvey = survey(primary = "Tester"),
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.BUG
        result?.reason shouldBe "prefilter:survey_detail"
    }

    "relog failure remains survey detail without a bot reply marker" {
        val result =
            RoutePrefilter.classify(
                context(
                    text = "релог делал не помогло",
                    activeBugSurvey = survey(primary = "Tester"),
                ),
                config,
            )
        result?.intent shouldBe RouteIntent.BUG
        result?.reason shouldBe "prefilter:survey_detail"
    }

    "observed production noise replay uses zero LLM routes" {
        val observedSkipMessages =
            listOf(
                "туфикс",
                "tufix",
                "tufix_TW",
                "tuf_TW",
                "tufix",
                "tuf_TW",
                "tufix",
                "tufix",
                "тут есть мало знакомый стример ярос туф",
                "тут есть мало знакомый стример ярос туф",
                "100000",
                "sprucraft хай",
                "sprucraft хай",
                "sprucraft хай",
                "Здаров",
                "Братан Я В Городе-Вардена",
                "пон",
                "gold",
                "кто может продать 6 мембран фантома",
                "кто может продать 6 мембран фантома",
                "кто может продать 6 мембран фантома",
                "кто может продать 6 мембран фантома",
                "Я",
                "не могу",
                "окак",
                "кто может продать 6 мембран фантома :((",
                "скорен!!!",
                "сука",
                "муся пришла и мне впн вырубила",
                "сука",
                "ADS",
                "мембрана фантома",
                "Спасибо, чем обязан?",
                "derevyshka",
            )

        observedSkipMessages.size shouldBe 34
        observedSkipMessages.forEach { text ->
            val result = RoutePrefilter.classify(context(text), config)
            result?.intent shouldBe RouteIntent.SKIP
            result?.model shouldBe "prefilter"
        }
    }
})

private fun context(
    text: String,
    raw: String = text,
    player: String = "Tester",
    directedAtBot: Boolean = false,
    continuationWithBot: Boolean = false,
    botRepliesInThread: Int = 0,
    activeBugSurvey: BugSurveySession? = null,
): RouterContext =
    RouterContext(
        message =
            InboundMessage(
                player = player,
                rawText = raw,
                displayText = text,
                timestampMs = 1L,
                server = "survival",
                source = InboundMessage.Source.GAME,
            ),
        meta =
            InboundMeta(
                directedAtBot = directedAtBot,
                replyToBot = continuationWithBot,
                continuationWithBot = continuationWithBot,
                secondsSinceBot = if (continuationWithBot) 10 else null,
                replyToPlayer = null,
                botRepliesInThread = botRepliesInThread,
            ),
        recentChat = emptyList(),
        openTicket = null,
        recentOpenTickets = emptyList(),
        recentRoutes = emptyList(),
        activeBugSurvey = activeBugSurvey,
    )

private fun survey(primary: String): BugSurveySession =
    BugSurveySession(
        player = primary,
        startedAtMs = 1L,
        lastActivityAtMs = 1L,
    )
