package ru.arc.ai.tools;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class ToolResponse {
    public UUID uuid;
    public String result;
    public String serverName;
}
