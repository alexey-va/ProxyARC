package ru.arc.ai.tools;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Data
@Builder
public class ToolMessage {
    public UUID uuid;
    public String toolName;
    public Tool toolDto;
    public long timestamp;

    transient public CompletableFuture<ToolResult> responseFuture;
    @Builder.Default
    transient public ToolResult toolResult = new ToolResult();
    transient public boolean atLeastOneResponse;
}
