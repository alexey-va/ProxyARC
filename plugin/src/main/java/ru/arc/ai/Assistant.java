package ru.arc.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.*;
import lombok.extern.slf4j.Slf4j;
import ru.arc.ai.tools.Tool;
import ru.arc.ai.tools.Tools;
import ru.arc.config.Config;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

import static ru.arc.ai.Defaults.DEFAULT_SYSTEM;

@Slf4j
public class Assistant {

    Config config;
    String apiBaseUrl;
    String type;
    OpenAIClient client;
    String prompt;
    long leftConversationUntil = 0;
    Deque<ChatCompletionMessageParam> history = new ConcurrentLinkedDeque<>();
    public static List<Assistant> assistants = new ArrayList<>();
    CompletableFuture<Optional<String>> currentRequest;


    public Assistant(Config config, String type) {
        this.config = config;
        this.apiBaseUrl = config.string("api-base-url", "https://openrouter.ai/api/v1");
        this.type = type;
        updateClient();
        loadPrompt();
        assistants.add(this);
    }

    public void reload() {
        updateClient();
        loadPrompt();
        history.clear();
        this.currentRequest = null;
        this.leftConversationUntil = 0;
    }

    private void loadPrompt() {
        try {
            var promptFolder = config.getFolder().resolve("prompts");
            if (!Files.exists(promptFolder)) Files.createDirectories(promptFolder);
            var promptPath = promptFolder.resolve(type + ".txt");
            if (!Files.exists(promptPath)) {
                Files.createFile(promptPath);
                Files.writeString(promptPath, DEFAULT_SYSTEM);
                this.prompt = DEFAULT_SYSTEM;
            } else {
                this.prompt = new String(Files.readAllBytes(promptPath));
            }
        } catch (Exception e) {
            log.info("Error reading prompt file", e);
            this.prompt = DEFAULT_SYSTEM;
        }
    }

    public void leaveForTime(int minutes) {
        log.info("Assistant of type {} is leaving for {} minutes", type, minutes);
        this.leftConversationUntil = System.currentTimeMillis() + (long) minutes * 60 * 1000;
    }

    private void updateClient() {
        var apiKey = config.string("api-key", "none");
        if (apiKey.equals("none")) {
            log.error("API key is not set for assistant type {}", type);
            client = null;
        } else {
            this.client = createClient(apiKey);
        }
    }

    private OpenAIClient createClient(String apiKey) {
        var proxyEnabled = config.bool("proxy.enabled", true);
        var proxyHost = config.string("proxy.host", "127.0.0.1");
        var proxyPort = config.integer("proxy.port", 8888);
        var builder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.of(10, ChronoUnit.SECONDS))
                .baseUrl(apiBaseUrl);
        if (proxyEnabled) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            builder.proxy(proxy);
        }
        return builder.build();
    }

    public void addChatMessage(String message, String player) {
        history.addLast(ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder()
                        .content(message)
                        .name(player)
                        .build()
        ));
        int maxHistory = config.integer(type + ".max-history", 20);
        while (history.size() > maxHistory) {
            log.info("Trimming chat history, size: {}", history.size());
            history.pollFirst();
        }
    }

    public CompletableFuture<Optional<String>> tryEnqueue() {
        if (!config.bool(type + ".enabled", true)) {
            log.info("Assistant of type {} is disabled", type);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (client == null) {
            updateClient();
            if (client == null) {
                log.info("Assistant client is not initialized, check API key");
                return CompletableFuture.completedFuture(Optional.empty());
            }
        }
        if (System.currentTimeMillis() < leftConversationUntil) {
            log.info("Assistant of type {} is currently away until {}", type, new Date(leftConversationUntil));
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (currentRequest != null && currentRequest.isDone()) {
            currentRequest = null;
        }
        if (currentRequest != null) {
            log.info("Request already in progress");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        this.currentRequest = sendRequest(0);
        return currentRequest;
    }

    private CompletableFuture<Optional<String>> sendRequest(int depth) {
        var builder = ChatCompletionCreateParams.builder()
                .addSystemMessage(getSystemMessage())
                .temperature(getTemperature())
                .model(getModel());
        for (var message : history) {
            builder.addMessage(message);
        }
        for (var tool : getTools()) {
            builder.addTool(tool);
        }
        var params = builder.build();

        return CompletableFuture.supplyAsync(() -> {
            try {
                var response = client.chat().completions().create(params);
                var message = response.choices().getFirst().message();
                history.addLast(ChatCompletionMessageParam.ofAssistant(message.toParam()));
                var toolCalls = message.toolCalls().orElse(List.of());
                toolCalls.forEach(toolCall -> {
                    var result = executeTool(toolCall);
                    history.addLast(ChatCompletionMessageParam.ofTool(
                            ChatCompletionToolMessageParam.builder()
                                    .toolCallId(toolCall.asFunction().id())
                                    .contentAsJson(result)
                                    .build()
                    ));
                });
                if (!toolCalls.isEmpty() && depth < 3) {
                    log.info("Tool calls detected, sending follow-up request");
                    return sendRequest(depth + 1).join();
                } else {
                    log.info("No tool calls, returning response\n{}", message.content().orElse(""));
                    var messageContent = message.content().orElse("");
                    if (messageContent.equalsIgnoreCase("пропускаю")) {
                        return Optional.empty();
                    }
                    if (messageContent.trim().isEmpty()) {
                        return Optional.empty();
                    }
                    return Optional.of(messageContent);
                }
            } catch (Exception e) {
                log.error("Error during chat completion", e);
                return Optional.empty();
            }
        });
    }

    private String getSystemMessage() {
        return prompt;
    }

    private String getModel() {
        return config.string(type + ".model", "x-ai/grok-4-fast:free");
    }

    private double getTemperature() {
        return config.real(type + ".temperature", 0.7);
    }

    private Collection<Class<? extends Tool>> getTools() {
        var toolNames = config.stringList(type + ".tools", List.of()).stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        return Tools.getAllTools()
                .stream()
                .filter(it -> toolNames.contains(it.getSimpleName().toLowerCase()))
                .collect(Collectors.toSet());
    }

    private Object executeTool(ChatCompletionMessageToolCall toolCall) {
        var toolClass = Tools.getTool(toolCall.asFunction().function().name());
        if (toolClass == null) {
            return "Unknown tool: " + toolCall.asFunction().function().name();
        }
        try {
            var tool = toolCall.asFunction().function().arguments(toolClass);
            log.info("Executing tool {}", tool);
            return tool.execute(this);
        } catch (Exception e) {
            return "Error executing tool: " + e.getMessage();
        }
    }


}
