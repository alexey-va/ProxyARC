package ru.arc.ai.routing.router

import ru.arc.config.Config
import java.nio.file.Files
import java.nio.file.Path

class RouterPrompt private constructor(
    val systemPrompt: String,
) {
    companion object {
        private val DEFAULT_PROMPT: String =
            RouterPrompt::class.java.getResourceAsStream("/prompts/router.txt")
                ?.bufferedReader()
                ?.readText()
                ?.trim()
                ?: """
                Ты — роутер Скорена. Ответь JSON: {"intent":"skip|chat|bug","confidence":0.0-1.0,"reason":"..."}
                """.trimIndent()

        fun forTest(systemPrompt: String): RouterPrompt = RouterPrompt(systemPrompt)

        fun load(config: Config, promptType: String = "router"): RouterPrompt {
            val promptFolder = config.dataFolder.toPath().resolve("prompts")
            if (!Files.exists(promptFolder)) {
                Files.createDirectories(promptFolder)
            }
            val promptPath = promptFolder.resolve("$promptType.txt")
            if (!Files.exists(promptPath)) {
                copyBundledPrompt(promptPath, promptType)
            }
            if (!Files.exists(promptPath)) {
                return RouterPrompt(DEFAULT_PROMPT)
            }
            val text = Files.readString(promptPath).trim()
            return RouterPrompt(if (text.isEmpty()) DEFAULT_PROMPT else text)
        }

        private fun copyBundledPrompt(promptPath: Path, promptType: String) {
            RouterPrompt::class.java.getResourceAsStream("/prompts/$promptType.txt")?.use { input ->
                Files.copy(input, promptPath)
            }
        }
    }
}
