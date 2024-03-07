package ru.arc;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import lombok.SneakyThrows;
import net.kyori.adventure.text.Component;

import java.awt.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class JippityConversation {
    private String apiKey;
    final Config config;
    final ChatHistory chatHistory;

    record MessageHistory(String role, String content) {
    }

    Deque<MessageHistory> messageHistories = new ArrayDeque<>();
    Gson gson = new Gson();

    @SneakyThrows
    public JippityConversation(Config config, ChatHistory chatHistory) {
        this.chatHistory = chatHistory;
        this.config = config;
        apiKey = config.string("jippity-api-key", "none");
    }

    @SneakyThrows
    public CompletableFuture<Component> sendOpenaiApiRequest(String message) {
        return CompletableFuture.supplyAsync(() -> {
            if (apiKey.equals("none")) {
                System.out.println("Jippity API key is not set");
                return null;
            }
            try (HttpClient client = HttpClient.newHttpClient()) {
                Map<String, Object> map = new HashMap<>();
                map.put("model", "gpt-3.5-turbo");
                ArrayList<Map<String, String>> list = new ArrayList<>();
                list.add(Map.of(
                        "role", "system",
                        "content", config.string("jippity-system", "Ты веселый чел в чате. Развлекай нас.") +
                                " .История последних собщений чата:" + chatHistory.asString()));
                messageHistories.forEach(mh -> list.add(Map.of("role", mh.role, "content", mh.content)));
                list.add(Map.of("role", "user", "content", message));
                map.put("messages", list);
                map.put("max_tokens", 100);
                map.put("temperature", 0.7);
                String json = gson.toJson(map);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.proxyapi.ru/openai/v1/chat/completions"))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonResponse jsonResponse = gson.fromJson(resp.body(), JsonResponse.class);
                if (messageHistories.size() > 30) {
                    messageHistories.poll();
                }
                String mes = jsonResponse.choices.get(0).message.content;
                messageHistories.add(new MessageHistory("user", message));
                messageHistories.add(new MessageHistory("assistant", mes));
                return Utils.mm("<gold>"+config.string("jippity-name", "ИИ")+" <gray>»<white> " + mes);
            } catch (Exception e) {
                return null;
            }
        });
    }
}


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

    class Choice {
        public int index;
        public Message message;
        public Object logprobs; // Use specific type if needed

        @SerializedName("finish_reason")
        public String finishReason;
    }

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
