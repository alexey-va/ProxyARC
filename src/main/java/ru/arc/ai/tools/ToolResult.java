package ru.arc.ai.tools;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@NoArgsConstructor
public class ToolResult {
    public Map<String, String> serverResults = new ConcurrentHashMap<>();
}
