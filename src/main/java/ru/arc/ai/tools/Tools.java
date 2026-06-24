package ru.arc.ai.tools;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Tools {

    static {
        tools = new ConcurrentHashMap<>();
        addTool(DefaultTools.LeaveForTime.class);
        addTool(DefaultTools.GetBalTop.class);
        addTool(DefaultTools.GetPlayerInfo.class);
    }

    private static final Map<String, Class<? extends Tool>> tools;

    public static void addTool(Class<? extends Tool> toolClass) {
        tools.put(toolClass.getSimpleName().toLowerCase(), toolClass);
    }

    public static Class<? extends Tool> getTool(String name) {
        return tools.get(name.toLowerCase());
    }

    public static Collection<Class<? extends Tool>> getAllTools() {
        return tools.values();
    }
}
