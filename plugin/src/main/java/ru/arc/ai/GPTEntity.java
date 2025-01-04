package ru.arc.ai;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import ru.arc.Utils;
import ru.arc.config.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class GPTEntity {

    private String apiKey;
    final Config config;
    String archetype;
    ChatHistory chatHistory;

    Gson gson = new Gson();
    HttpClient client;

    @SneakyThrows
    public GPTEntity(Config config, String archetype, ChatHistory chatHistory) {
        this.config = config;
        this.chatHistory = chatHistory;
        apiKey = config.string("ai.api-key", "none");
        this.archetype = archetype;
        client = HttpClient.newHttpClient();
    }

    public void resetHistory() {
        chatHistory.clear();
    }

    public record ModerResponse(ModerationResponse message, String comment) {
    }

    public enum ModerationResponse {
        OK, BAD
    }

    @SneakyThrows
    public CompletableFuture<Optional<ModerResponse>> getModerResponse(String playerName, String message) {
        if (apiKey.equals("none")) {
            log.error("API key is not set");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        List<Map<String, String>> history = new ArrayList<>();
        history.add(Map.of("role", "user", "content", message));

        String okMarker = config.string("ai." + archetype + ".moderation-ok-marker", "ok");
        String badMarker = config.string("ai." + archetype + ".moderation-bad-marker", "bad");
        String commentMarker = config.string("ai." + archetype + ".moderation-comment-marker", "comment:");

        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> map = new HashMap<>();

            map.put("model", config.string("ai.model", config.string("ai." + archetype + ".model", "gpt-4o-mini")));

            StringBuilder systemMessageBuilder = new StringBuilder();
            for (String s : config.stringList("ai.common-system-messages", List.of())) {
                systemMessageBuilder.append(s.replace("%player_name%", playerName)).append("\n");
            }
            for (String s : config.stringList("ai." + archetype + ".system", List.of())) {
                systemMessageBuilder.append(s.replace("%player_name%", playerName)).append("\n");
            }

            List<Map<String, String>> messages = new ArrayList<>(history);
            messages.add(Map.of(
                    "role", "system",
                    "content", systemMessageBuilder.toString())
            );


            map.put("messages", messages);
            map.put("max_tokens", config.integer("ai." + archetype + ".max-tokens", 250));
            map.put("temperature", config.real("ai." + archetype + ".temperature", 0.7));
            String json = gson.toJson(map);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.proxyapi.ru/openai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            try {
                var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonResponse jsonResponse = gson.fromJson(resp.body(), JsonResponse.class);
                String gptResponse = jsonResponse.choices.getFirst().message.content;

                ModerationResponse response = gptResponse.contains(okMarker)
                        ? ModerationResponse.OK :
                        gptResponse.contains(badMarker) ? ModerationResponse.BAD : null;

                String comment = gptResponse.contains(commentMarker)
                        ? gptResponse.substring(gptResponse.indexOf(commentMarker) + commentMarker.length()).trim()
                        : null;
                return Optional.of(new ModerResponse(response, comment));
            } catch (Exception e) {
                log.error("Failed to get response from GPT", e);
                return Optional.empty();
            }
        });
    }

    @SneakyThrows
    public CompletableFuture<Optional<String>> getResponse(String playerName, String message) {
        if (apiKey.equals("none")) {
            log.error("API key is not set");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        List<Map<String, String>> history = new ArrayList<>();
        if (chatHistory != null) {
            addPlayerMessage(playerName, message);
            chatHistory.entries().forEach(entry ->
                    history.add(Map.of("role", entry.isPlayer() ? "user" : "assistant", "content", entry.text())));
        } else {
            history.add(Map.of("role", "user", "content", message));
        }

        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> map = new HashMap<>();

            map.put("model", config.string("ai.model", config.string("ai." + archetype + ".model", "gpt-4o-mini")));

            StringBuilder systemMessageBuilder = new StringBuilder();
            for (String s : config.stringList("ai.common-system-messages", List.of())) {
                systemMessageBuilder.append(s.replace("%player_name%", playerName)).append("\n");
            }
            for (String s : config.stringList("ai." + archetype + ".system", List.of())) {
                systemMessageBuilder.append(s.replace("%player_name%", playerName)).append("\n");
            }

            List<Map<String, String>> messages = new ArrayList<>(history);
            messages.add(Map.of(
                    "role", "system",
                    "content", systemMessageBuilder.toString())
            );


            map.put("messages", messages);
            map.put("max_tokens", config.integer("ai." + archetype + ".max-tokens", 250));
            map.put("temperature", config.real("ai." + archetype + ".temperature", 0.7));
            String json = gson.toJson(map);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.proxyapi.ru/openai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            try {
                var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonResponse jsonResponse = gson.fromJson(resp.body(), JsonResponse.class);
                String gptResponse = jsonResponse.choices.getFirst().message.content;
                chatHistory.addBotMessage(gptResponse);
                return Optional.of(gptResponse);
            } catch (Exception e) {
                log.error("Failed to get response from GPT", e);
                return Optional.empty();
            }
        });
    }

    public Component toAiMessageComponent(String mes) {
        return Utils.legacy(config.string("giga-chat.prefix", "&6%name% &7» ")
                .replace("%name%", config.string("giga-chat.name", "&6ИИ")) + mes);
    }

    public void addPlayerMessage(String username, String message) {
        String historyFormat = config.string("ai." + archetype + ".history-format", "%player_name% написал %message%");
        String historyMessage = historyFormat
                .replace("%player_name%", username)
                .replace("%message%", message);
        chatHistory.addPlayerMessage(historyMessage);
    }
}

enum Type {
    OPENAI, GIGACHAT
}

@ToString
class JsonResponse {
    public String id;
    public String object;
    public long created;
    public String model;
    public List<Choice> choices;
    public Usage usage;

    @SerializedName("system_fingerprint")
    public String systemFingerprint;
}

@ToString
class Choice {
    public int index;
    public Message message;
    public Object logprobs; // Use specific type if needed

    @SerializedName("finish_reason")
    public String finishReason;
}

@ToString
class Message {
    public String role;
    public String content;
}

class Usage {
    @SerializedName("prompt_tokens")
    public int promptTokens;

    @SerializedName("completion_tokens")
    public int completionTokens;

    @SerializedName("total_tokens")
    public int totalTokens;
}
