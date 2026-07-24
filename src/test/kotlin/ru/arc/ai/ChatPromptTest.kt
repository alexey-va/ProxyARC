package ru.arc.ai

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.string.shouldContain

class ChatPromptTest : FreeSpec({
    "chat.txt" - {
        val prompt =
            javaClass.getResourceAsStream("/prompts/chat.txt")!!
                .bufferedReader()
                .readText()

        "humor section dark humor and anti childish" {
            prompt shouldContain "dark humor"
            prompt shouldContain "doctor jokes"
            prompt shouldContain "приходит мужик"
        }

        "gros femboy folklore in humor" {
            prompt shouldContain "femboy socks"
            prompt shouldContain "грос"
        }

        "player replies Russian only" {
            prompt shouldContain "Russian only"
            prompt shouldContain "SKIP"
        }

        "anti annoyance rules are explicit" {
            prompt shouldContain "Never chase attention"
            prompt shouldContain "profanity is rare"
            prompt shouldContain "не пиши"
            prompt shouldContain "third-person mentions"
            prompt shouldContain "Would silence be less annoying?"
        }

        "usefulness comes before persona" {
            prompt shouldContain "Answer the actual question"
            prompt shouldContain "invented server facts"
        }

        "prompt body is English" {
            prompt shouldContain "You are Скорен"
        }
    }
})
