package ru.arc.ai.tools;

import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import ru.arc.xserver.ChannelListener;
import ru.arc.xserver.RedisManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class ToolsMessager implements ChannelListener {

    public static ToolsMessager instance;

    public static final String CHANNEL_REQUEST_TOOLS = "arc.ai_tools_req";
    public static final String CHANNEL_RESPONSE_TOOLS = "arc.ai_tools_res";
    private static final long RESPONSE_TIMEOUT_MS = 30000;
    private static final Map<UUID, ToolMessage> pendingResponses = new ConcurrentHashMap<>();

    final RedisManager redisManager;
    final int maxResponses;

    private static final Gson gson = new Gson();

    @Override
    public void consume(String channel, String message, String originServer) {
        ToolResponse toolResponse = gson.fromJson(message, ToolResponse.class);
        ToolMessage toolMessage = pendingResponses.get(toolResponse.uuid);
        if (toolMessage != null) {
            toolMessage.toolResult.serverResults.put(originServer, toolResponse.result);
            if (toolMessage.atLeastOneResponse) {
                toolMessage.responseFuture.complete(toolMessage.toolResult);
            } else if (toolMessage.toolResult.serverResults.size() >= maxResponses) {
                toolMessage.responseFuture.complete(toolMessage.toolResult);
            }
        }
        trimTimedOutResponses();
    }

    public CompletableFuture<ToolResult> sendToolMessage(Tool toolDto, boolean atLeastOneResponse) {
        trimTimedOutResponses();

        UUID uuid = UUID.randomUUID();
        CompletableFuture<ToolResult> future = new CompletableFuture<>();
        final ToolMessage toolMessage = ToolMessage.builder()
                .uuid(uuid)
                .toolDto(toolDto)
                .toolName(toolDto.getClass().getSimpleName())
                .timestamp(System.currentTimeMillis())
                .atLeastOneResponse(atLeastOneResponse)
                .responseFuture(future)
                .build();
        CompletableFuture.runAsync(() -> {
            String json = gson.toJson(toolMessage);
            redisManager.publish(CHANNEL_REQUEST_TOOLS, json);
        });
        pendingResponses.put(uuid, toolMessage);

        return future;
    }

    private void trimTimedOutResponses() {
        long now = System.currentTimeMillis();
        pendingResponses.entrySet()
                .removeIf(entry -> now - entry.getValue().timestamp > RESPONSE_TIMEOUT_MS);
        pendingResponses.entrySet()
                .removeIf(entry -> entry.getValue().responseFuture.isDone());
    }

}
