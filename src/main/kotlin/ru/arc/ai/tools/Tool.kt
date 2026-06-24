package ru.arc.ai.tools

import ru.arc.ai.Assistant

interface Tool {
    fun execute(assistant: Assistant?): Any?
}
