package ru.arc.ai.tools

import java.util.UUID

data class ToolResponse(
    @JvmField var uuid: UUID? = null,
    @JvmField var result: String? = null,
    @JvmField var serverName: String? = null,
)
