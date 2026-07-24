package ru.arc.ai.routing.router

import ru.arc.ai.tickets.PlayerWorldNames

/**
 * Lightweight bug-report detection for router fallback when LLM returns empty JSON.
 */
object RouterBugHeuristic {
    private val commandMention = Regex("""(?iu)(?:^|\s)/[a-z0-9_:-]+""")
    private val assistantAddressToken =
        Regex(
            """(?iu)(?:@?скорен|@addscoren)(?=$|[\s,.!?])|""" +
                """(?:^|[,])\s*(?:(?:эй|слушай)\s+)?@?бот(?=$|[\s,.!?])""",
        )

    private val jokePatterns =
        listOf(
            "как баг",
            "это фича",
            "не баг",
            "шучу",
            "анекдот",
            "расскажи анекдот",
            "го в войс",
        )

    private val reportedBug =
        Regex(
            """(?iuU)(?:""" +
                """\b(?:у\s+меня|наш(?:е|ё)л|обнаружил|поймал|заметил)\s+""" +
                """(?:есть\s+)?(?:баг|bug)\b|""" +
                """\b(?:баг|bug)\s+(?:с|в|на)\s+\S+|""" +
                """\b(?:это|похоже\s+на)\s+(?:баг|bug)\b)""",
        )
    private val failedAction =
        Regex(
            """(?iuU)\bне\s+(?:работает|работают|работало|работали|пашет|пашут|""" +
                """открывается|открываются|появляется|появляются|приходит|приходят|""" +
                """телепортирует|телепортируется|выда(?:е|ё)тся|выдаются|""" +
                """покупается|покупаются|прода(?:е|ё)тся|продаются|""" +
                """списывается|сохраняется|загружается)\b""",
        )
    private val crashOrLag =
        Regex(
            """(?iuU)\b(?:крашит|крашится|крашнуло|вылетает|вылетел|вылетело|""" +
                """лагает|глючит)\b""",
        )
    private val duplicate =
        Regex("""(?iuU)\b(?:дюп(?:ается|нулся|нулось|нуть)?|дублиру(?:ет|ется|ются))\b""")
    private val technicalError =
        Regex(
            """(?iuU)\b(?:null|exception)\b|""" +
                """\b(?:пишет|выда(?:е|ё)т|появляется|вылезает)\s+ошибк""",
        )
    private val brokenState =
        Regex("""(?iuU)\b(?:сломано|сломался|сломалась|сломалось|сломались|поломан\w*)\b""")
    private val tpsFailure =
        Regex("""(?iuU)\b(?:tps|тпс)\b.{0,24}\b(?:упал|падает|низк\w*|[0-9]+)\b""")
    private val lossSubject =
        """(?:предмет\w*|вещ\w*|рес\w*|ресурс\w*|лут\w*|баланс\w*|""" +
            """деньг\w*|монет\w*|ключ\w*|инвентар\w*)"""
    private val lossAction = """(?:пропал\w*|пропада\w*|исчез\w*|съело|съедает|съедаются)"""
    private val itemLoss =
        Regex("""(?iuU)(?:$lossSubject.{0,40}$lossAction|$lossAction.{0,40}$lossSubject)""")
    private val exploit =
        Regex(
            """(?iuU)(?:""" +
                """(?:выда(?:е|ё)т|получил|купил|покупается|забрал).{0,40}бесплатн|""" +
                """бесплатн.{0,40}(?:выда|получ|купил)|""" +
                """двойн\w*.{0,24}(?:выдач|награ|лут|списан|предмет)|""" +
                """(?:выдал|получил).{0,24}(?:два|двойн))""",
        )
    private val progressReset =
        Regex(
            """(?iuU)(?:""" +
                """(?:сбрасыва\w*|обнуля\w*|сбросил\w*|обнулило).{0,40}""" +
                """(?:прогресс|изучен|навык|уров|баланс|инвентар)|""" +
                """(?:прогресс|изучен|навык|уров|баланс|инвентар).{0,40}""" +
                """(?:сбрасыва\w*|обнуля\w*|сбросил\w*|обнулило))""",
        )
    private val silentCommand =
        Regex("""(?iuU)\b(?:молчит|ничего|ниче|тишина|ноль\s+реакции)\b""")
    private val namedSilentCommand =
        Regex(
            """(?iuU)\b/?(?:rtp|ртп)\b.{0,32}\b(?:молчит|ничего|ниче|тишина|ноль\s+реакции)\b""",
        )

    private val externalProblemSignals =
        listOf(
            "интернет",
            "вайфай",
            "wi-fi",
            "wifi",
            "впн",
            "vpn",
            "дискорд",
            "discord",
            "телеграм",
            "telegram",
            "телефон",
            "микрофон",
            "наушник",
            "браузер",
            "сайт",
            "винда",
            "windows",
            "компьютер",
            "ноутбук",
        )
    private val optOutPatterns =
        listOf(
            Regex("""(?iuU)\bне\s+пиши(?:\s+мне)?\b"""),
            Regex("""(?iuU)\b(?:отстань|заткнись|выруби|молчи)\b"""),
            Regex("""(?iuU)\bвыключи\s+скорен(?:а)?\b"""),
            Regex("""(?iuU)\bкак\s+(?:скорен(?:а)?\s+вырубить|вырубить\s+скорен(?:а)?)\b"""),
            Regex("""(?iuU)\bя\s+не\s+с\s+тобой\b"""),
        )

    fun looksLikeJoke(text: String): Boolean {
        val lower = text.trim().lowercase()
        return jokePatterns.any { lower.contains(it) }
    }

    /** Player explicitly asks the bot to stop messaging them. */
    fun looksLikeOptOut(text: String): Boolean {
        val lower = text.trim().lowercase()
        return optOutPatterns.any { it.containsMatchIn(lower) }
    }

    /** Scream/laugh spam without bug details — skip survey nudge PMs. */
    fun looksLikeTrollNoise(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.length > 80) return false
        if (looksLikeBugReport(lower) || looksLikeUiBug(text)) return false
        val letters = lower.filter { it.isLetter() }
        if (letters.length >= 8) {
            val distinct = letters.toSet().size
            if (distinct <= 2) return true
        }
        return listOf("хах", "лол", "кек", "рофл", "пох", "похую", "поху").any { lower.contains(it) } &&
            lower.length <= 48
    }

    fun looksLikeBugReport(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.length < 3) return false
        if (looksLikeJoke(lower) && !looksLikeUiBug(text)) return false
        if (externalProblemSignals.any { lower.contains(it) }) return false
        if (looksLikeVagueBugClaim(lower)) return true
        if (looksLikeUiBug(text)) return true
        return reportedBug.containsMatchIn(lower) ||
            failedAction.containsMatchIn(lower) ||
            crashOrLag.containsMatchIn(lower) ||
            duplicate.containsMatchIn(lower) ||
            technicalError.containsMatchIn(lower) ||
            brokenState.containsMatchIn(lower) ||
            tpsFailure.containsMatchIn(lower) ||
            itemLoss.containsMatchIn(lower) ||
            exploit.containsMatchIn(lower) ||
            progressReset.containsMatchIn(lower) ||
            (
                commandMention.containsMatchIn(lower) &&
                    silentCommand.containsMatchIn(lower)
            ) || namedSilentCommand.containsMatchIn(lower)
    }

    /** «есть бага», «баг» without details — must open bug survey, not router skip. */
    fun looksLikeVagueBugClaim(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.length > 48) return false
        if (looksLikeJoke(lower) && !looksLikeUiBug(text)) return false
        if (looksLikeSurveyDetail(text)) return false
        if (lower in setOf("баг", "бага", "bug")) return true
        return listOf(
            "у меня баг",
            "у меня бага",
            "есть бага",
            "есть баг",
            "баг есть",
            "есть bug",
            "bug есть",
            "ладно серьёзно есть бага",
            "ладно серьезно есть бага",
        ).any { lower.contains(it) }
    }

    /** Menu/GUI wrong text — real bug even if message insults the bot. */
    fun looksLikeUiBug(text: String): Boolean {
        val lower = text.trim().lowercase()
        val uiSubject =
            listOf("меню", "gui", "интерфейс", "кнопк").any { lower.contains(it) }
        if (!uiSubject) return false
        return listOf(
            "не работ",
            "не паш",
            "не откры",
            "написано",
            "надпис",
            "неверн",
            "неправ",
            "null",
            "пуст",
            "пропал",
            "сломан",
        ).any { lower.contains(it) }
    }

    /** Public-chat acknowledgements that should not wake the assistant again. */
    fun looksLikeLowValueContinuation(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower in setOf("ок", "ага", "пон", "понял", "ясно", "+", "+1", "норм", "кайф", "спс", "спасибо")) {
            return true
        }
        return listOf(
            "го в войс",
            "кто в войс",
            "кто пойдёт",
            "кто пойдет",
            "чё делаете",
            "че делаете",
        ).any { lower.contains(it) }
    }

    /** Explicit «скорен, спасибо» / «бот, ок» should not provoke another reply. */
    fun looksLikeLowValueBotAddress(text: String): Boolean {
        val withoutAddress =
            assistantAddressToken
                .replace(text.lowercase(), " ")
                .trim()
                .trim(',', '.', '!', '?', ':', ';')
                .trim()
        return withoutAddress.isNotEmpty() && looksLikeLowValueContinuation(withoutAddress)
    }

    fun looksLikeResolved(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.contains("не работает") || lower.contains("не пашет")) return false
        return listOf(
            "всё ок",
            "все ок",
            "починил",
            "починилось",
            "мой косяк",
            "не баг",
            "ложная тревога",
            "работает теперь",
            "заработало",
            "проблему решил",
            "проблема решилась",
            "исправилось",
            "adventure",
            "адвенч",
        ).any { lower.contains(it) }
    }

    fun looksLikeCloseTicket(text: String): Boolean {
        val lower = text.trim().lowercase()
        return listOf(
            "закрой тикет",
            "закрой rb",
            "закрывай тикет",
            "закрыть тикет",
            "close ticket",
        ).any { lower.contains(it) }
    }

    /** Smalltalk to Скорен during survey — not a bug follow-up. */
    fun looksLikeOfftopicSmalltalk(text: String): Boolean {
        if (looksLikeBugReport(text) || looksLikeUiBug(text)) return false
        val lower = text.trim().lowercase()
        return listOf(
            "видел",
            "новый спавн",
            "как дела",
            "го в войс",
            "кто тут",
            "привет скорен",
            "скорен а ты",
        ).any { lower.contains(it) }
    }

    fun looksLikeSurveyDetail(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (PlayerWorldNames.inferFromText(lower) != null) return true
        if (commandMention.containsMatchIn(lower)) return true
        return listOf(
            "вчера",
            "тоже",
            "ещё",
            "еще",
            "флаг",
            "worldguard",
            "world guard",
            "pvp",
            "пкм",
            "лкм",
            "релог",
            "перезаш",
            "перезапуск",
        ).any { lower.contains(it) }
    }
}
