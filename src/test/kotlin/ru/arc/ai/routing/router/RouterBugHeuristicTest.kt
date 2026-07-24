package ru.arc.ai.routing.router

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class RouterBugHeuristicTest : FreeSpec({
    "looksLikeOptOut" - {
        RouterBugHeuristic.looksLikeOptOut("скорен не пиши мне я не с тобой") shouldBe true
        RouterBugHeuristic.looksLikeOptOut("КАК СКОРЕНА ВЫРУБИТЬ") shouldBe true
        RouterBugHeuristic.looksLikeOptOut("и кста у меня ресы пропадают") shouldBe false
        RouterBugHeuristic.looksLikeOptOut("после /rtp команда молчит") shouldBe false
    }

    "looksLikeTrollNoise" - {
        RouterBugHeuristic.looksLikeTrollNoise("ХАХАХАХАХ") shouldBe true
        RouterBugHeuristic.looksLikeTrollNoise("АААААААААААААА") shouldBe true
        RouterBugHeuristic.looksLikeTrollNoise("ваще похую") shouldBe true
        RouterBugHeuristic.looksLikeTrollNoise("сф быстрых машин сбрасывает изучение") shouldBe false
    }

    "looksLikeBugReport requires breakage rather than a feature noun" - {
        RouterBugHeuristic.looksLikeBugReport("rtp не работает") shouldBe true
        RouterBugHeuristic.looksLikeBugReport("скорен у меня ртп молчит") shouldBe true
        RouterBugHeuristic.looksLikeBugReport("скорен ртп жму и тишина ваще") shouldBe true
        RouterBugHeuristic.looksLikeBugReport("в меню написано скорен лох") shouldBe true
        RouterBugHeuristic.looksLikeBugReport("/ah sell съедает предмет") shouldBe true
        RouterBugHeuristic.looksLikeBugReport("vote ключ не приходит") shouldBe true
        RouterBugHeuristic.looksLikeBugReport("кто продаст ключ") shouldBe false
        RouterBugHeuristic.looksLikeBugReport("открой меню") shouldBe false
        RouterBugHeuristic.looksLikeBugReport("rtp го вместе") shouldBe false
        RouterBugHeuristic.looksLikeBugReport("текст красивый") shouldBe false
        RouterBugHeuristic.looksLikeBugReport("у меня интернет лагает") shouldBe false
        RouterBugHeuristic.looksLikeBugReport("дискорд не работает") shouldBe false
        RouterBugHeuristic.looksLikeBugReport("телефон глючит") shouldBe false
        RouterBugHeuristic.looksLikeBugReport("на сервере чат лагает") shouldBe true
        RouterBugHeuristic.looksLikeBugReport("на survival тпс упал до 5, не норм") shouldBe true
        RouterBugHeuristic.looksLikeBugReport("после рестарта /rtp просто молчит и ниче") shouldBe true
        RouterBugHeuristic.looksLikeBugReport("сф быстрых машин сбрасывает изучение") shouldBe true
        RouterBugHeuristic.looksLikeBugReport("у меня баг") shouldBe true
        RouterBugHeuristic.looksLikeBugReport("нашёл баг в /rtp") shouldBe true
        RouterBugHeuristic.looksLikeBugReport("баг с аукционом") shouldBe true
    }

    "looksLikeBugReport ignores ordinary words that merely share old stems" - {
        listOf(
            "не покупай ключ у него",
            "не продавай ему мембраны",
            "не открывай сундук без меня",
            "я не покупал донат",
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
            RouterBugHeuristic.looksLikeBugReport(text) shouldBe false
        }
    }

    "looksLikeLowValueContinuation filters acknowledgements" - {
        RouterBugHeuristic.looksLikeLowValueContinuation("пон") shouldBe true
        RouterBugHeuristic.looksLikeLowValueContinuation("спасибо") shouldBe true
        RouterBugHeuristic.looksLikeLowValueContinuation("а почему так") shouldBe false
        RouterBugHeuristic.looksLikeLowValueContinuation("нет, всё ещё не работает") shouldBe false
    }

    "looksLikeLowValueBotAddress filters direct acknowledgements" - {
        RouterBugHeuristic.looksLikeLowValueBotAddress("скорен, спасибо") shouldBe true
        RouterBugHeuristic.looksLikeLowValueBotAddress("бот ок") shouldBe true
        RouterBugHeuristic.looksLikeLowValueBotAddress("спасибо, скорен") shouldBe true
        RouterBugHeuristic.looksLikeLowValueBotAddress("скорен, а почему так") shouldBe false
        RouterBugHeuristic.looksLikeLowValueBotAddress("скорен, да") shouldBe false
    }

    "looksLikeResolved requires an explicit resolution" - {
        RouterBugHeuristic.looksLikeResolved("всё ок, починилось") shouldBe true
        RouterBugHeuristic.looksLikeResolved("мой косяк, не баг") shouldBe true
        RouterBugHeuristic.looksLikeResolved("уже заработало после перезахода, проблему решил") shouldBe true
        RouterBugHeuristic.looksLikeResolved("понял") shouldBe false
        RouterBugHeuristic.looksLikeResolved("нашёл ключ") shouldBe false
        RouterBugHeuristic.looksLikeResolved("извини, отошёл") shouldBe false
    }

    "looksLikeSurveyDetail recognizes commands but not arbitrary slashes" - {
        RouterBugHeuristic.looksLikeSurveyDetail("после /rtp кидает назад") shouldBe true
        RouterBugHeuristic.looksLikeSurveyDetail("/ah sell не работает") shouldBe true
        RouterBugHeuristic.looksLikeSurveyDetail("релог делал не помогло") shouldBe true
        RouterBugHeuristic.looksLikeSurveyDetail("ссылка https://example.org/guide") shouldBe false
        RouterBugHeuristic.looksLikeSurveyDetail("один/два") shouldBe false
    }
})
