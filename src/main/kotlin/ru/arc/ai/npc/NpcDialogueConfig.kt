package ru.arc.ai.npc

import ru.arc.ai.config.PromptFiles
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

data class NpcPersona(
    val id: String,
    val displayName: String,
    val promptFile: String,
    val model: String,
    val maxTokens: Int,
    val temperature: Double,
)

class NpcDialogueConfig private constructor(
    private val dataRoot: Path,
    private val config: Config,
) {
    val enabled: Boolean
        get() = config.bool("enabled", true)

    val personas: Map<String, NpcPersona>
        get() =
            config.keys("personas").associateWith { id ->
                NpcPersona(
                    id = id,
                    displayName = config.string("personas.$id.display-name", id),
                    promptFile = config.string("personas.$id.prompt-file", "prompts/npc/$id.txt"),
                    model = config.string("personas.$id.model", config.string("defaults.model", "openai/gpt-4o-mini")),
                    maxTokens =
                        config.integer("personas.$id.max-tokens", config.integer("defaults.max-tokens", 120))
                            .coerceIn(32, 250),
                    temperature =
                        config.real("personas.$id.temperature", config.real("defaults.temperature", 0.65))
                            .coerceIn(0.0, 1.5),
                )
            }

    fun systemPrompt(
        persona: NpcPersona,
        playerName: String,
    ): String {
        val common = PromptFiles.readText(dataRoot, COMMON_PROMPT).orEmpty()
        val role = PromptFiles.readText(dataRoot, persona.promptFile).orEmpty()
        return listOf(common, role)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .replace("%player_name%", playerName)
    }

    companion object {
        const val RESOURCE = "npc-dialogue.yml"
        const val COMMON_PROMPT = "prompts/npc/common.txt"

        fun load(dataRoot: Path): NpcDialogueConfig {
            Config.copyDefaultConfig(ConfigManager.bundledModuleResource(RESOURCE), dataRoot, replace = false)
            val result = NpcDialogueConfig(dataRoot, ConfigManager.ofModule(dataRoot, RESOURCE))
            result.personas.values.forEach { persona ->
                require(result.systemPrompt(persona, "Player").isNotBlank()) {
                    "Missing prompt for NPC persona ${persona.id}: ${persona.promptFile}"
                }
            }
            return result
        }
    }
}
