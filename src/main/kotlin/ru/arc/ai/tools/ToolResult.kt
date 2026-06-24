package ru.arc.ai.tools

import java.util.concurrent.ConcurrentHashMap

class ToolResult {
    @JvmField
    val serverResults: MutableMap<String, String> = ConcurrentHashMap()
}
