package ru.arc.ai.tools

import java.util.concurrent.ConcurrentHashMap

object Tools {
    private val tools: MutableMap<String, Class<out Tool>> = ConcurrentHashMap()

    init {
        addTool(DefaultTools.RememberFact::class.java)
        addTool(DefaultTools.ForgetFact::class.java)
        addTool(DefaultTools.LeaveForTime::class.java)
        addTool(DefaultTools.GetBalTop::class.java)
        addTool(DefaultTools.GetPlayerInfo::class.java)
        addTool(DefaultTools.GetInventory::class.java)
    }

    @JvmStatic
    fun addTool(toolClass: Class<out Tool>) {
        tools[toolClass.simpleName.lowercase()] = toolClass
    }

    @JvmStatic
    fun getTool(name: String): Class<out Tool>? = tools[name.lowercase()]

    @JvmStatic
    fun getAllTools(): Collection<Class<out Tool>> = tools.values
}
