package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class BugTicketPromptTest : FreeSpec({
    "bug-ticket.txt" - {
        val prompt =
            javaClass.getResourceAsStream("/prompts/bug-ticket.txt")!!
                .bufferedReader()
                .readText()

        "requires PM on vague report with open ticket" {
            prompt shouldContain "есть бага"
            prompt shouldContain "sendprivatemessage"
            prompt shouldContain "without tools"
        }

        "requires PM after createissueticket" {
            prompt shouldContain "createissueticket"
            prompt shouldContain "mandatory sendprivatemessage"
        }

        "completebugsurvey tool documented with its registered name" {
            prompt shouldContain "completebugsurvey"
            prompt shouldNotContain "completebugs urvey"
        }

        "sendglobalmessage tool documented" {
            prompt shouldContain "sendglobalmessage"
        }

        "close ticket uses closed title prefix" {
            prompt shouldContain "[Закрыт]"
            prompt shouldContain "status=closed"
        }

        "gros handles forum tickets not player" {
            prompt shouldContain "gros"
            prompt shouldContain "посмотри тикет"
        }

        "player messages must be Russian" {
            prompt shouldContain "Russian only"
            prompt shouldContain "PM STYLE"
        }

        "menu UI text is a real bug" {
            prompt shouldContain "меню"
            prompt shouldContain "updateissueticket"
            prompt shouldContain "скорен лох"
        }

        "algorithm requires tools before skip" {
            prompt shouldContain "TURN ALGORITHM"
            prompt shouldContain "After tools"
        }

        "prompt body is English" {
            prompt shouldContain "You are Скорен"
            prompt shouldNotContain "алгоритм каждого хода"
        }
    }
})
