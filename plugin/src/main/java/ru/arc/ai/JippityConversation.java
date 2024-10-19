package ru.arc.ai;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import lombok.SneakyThrows;
import lombok.ToString;
import net.kyori.adventure.text.Component;
import ru.arc.Utils;
import ru.arc.config.Config;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class JippityConversation {
    private String clientId;
    private String apiKey;
    private String scope;
    String clientSecret;
    String base64auth;
    SSLContext sslContext;
    OauthToken oauthToken;

    final Config config;
    //final ChatHistory chatHistory;
    final Path folder;
    Type type;

    public record MessageHistory(String role, String content, long timeStamp) {
    }

    public record OauthToken(@SerializedName("access_token") String token, @SerializedName("expires_at") long expires) {
    }

    Deque<MessageHistory> messageHistories = new ArrayDeque<>();
    Gson gson = new Gson();

    @SneakyThrows
    public JippityConversation(Config config, ChatHistory chatHistory, Path folder) {
        //this.chatHistory = chatHistory;
        this.config = config;
        this.folder = folder;
        //clientSecret = config.string("giga-chat.client-secret", "none");
        apiKey = config.string("giga-chat.api-key", "none");
        base64auth = config.string("giga-chat.base64-auth", "none");
        scope = config.string("giga-chat.scope", "none");
        type = Type.valueOf(config.string("giga-chat.type", "OPENAI").toUpperCase());
        setupCertificates();
    }

    public void resetHistory() {
        messageHistories.clear();
        //chatHistory.clear();
    }

    @SneakyThrows
    public void setupCertificates() {
        // Load the root CA certificate
        FileInputStream rootCAFileInputStream = new FileInputStream(folder.resolve("russian_trusted_root_ca.cer").toFile());
        CertificateFactory rootCACertificateFactory = CertificateFactory.getInstance("X.509");
        X509Certificate rootCACert = (X509Certificate) rootCACertificateFactory.generateCertificate(rootCAFileInputStream);

        // Load the sub CA certificate
        FileInputStream subCAFileInputStream = new FileInputStream(folder.resolve("russian_trusted_sub_ca.cer").toFile());
        CertificateFactory subCACertificateFactory = CertificateFactory.getInstance("X.509");
        X509Certificate subCACert = (X509Certificate) subCACertificateFactory.generateCertificate(subCAFileInputStream);


        // Create a KeyStore containing both the root CA and sub CA certificates
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("root_ca_certificate", rootCACert);
        ks.setCertificateEntry("sub_ca_certificate", subCACert);

        // Create a TrustManagerFactory that trusts the certificates
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        // Create an SSLContext with the TrustManager that trusts the certificates
        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);
    }

    public CompletableFuture<Void> setupOauthToken() {
        if (oauthToken != null && oauthToken.expires < System.currentTimeMillis())
            return CompletableFuture.completedFuture(null);
        return getOauthToken()
                .thenAccept(token -> oauthToken = token);
    }

    public CompletableFuture<OauthToken> getOauthToken() {
        return CompletableFuture.supplyAsync(() -> {
            if (base64auth.equals("none")) {
                System.out.println("Giga chat AUTH key is not set");
                return null;
            }
            HttpClient client;
            if (type == Type.GIGACHAT) client = HttpClient.newBuilder().sslContext(sslContext).build();
            else client = HttpClient.newHttpClient();
            try (client) {
                Map<String, String> parameters = new HashMap<>();
                parameters.put("scope", scope);
                String form = parameters.entrySet()
                        .stream()
                        .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                        .collect(Collectors.joining("&"));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(config.string("giga-chat.oauth-url",
                                "https://ngw.devices.sberbank.ru:9443/api/v2/oauth")))
                        .header("Authorization", "Basic " + base64auth)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        //.header("Accept", "application/json")
                        .header("RqUID", UUID.randomUUID().toString())
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
                var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                OauthToken token = gson.fromJson(resp.body(), OauthToken.class);
                return token;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    @SneakyThrows
    public CompletableFuture<String> sendOpenaiApiRequest(String message) {
        var future = type == Type.OPENAI ? CompletableFuture.completedFuture(null) :
                setupOauthToken();
        return future.thenApplyAsync((o) -> {
            String response = null;
            HttpClient client;
            if (type == Type.GIGACHAT) client = HttpClient.newBuilder().sslContext(sslContext).build();
            else client = HttpClient.newHttpClient();
            try (client) {
                Map<String, Object> map = new HashMap<>();
                map.put("model", config.string("giga-chat.model", "GigaChat:latest"));
                ArrayList<Map<String, String>> list = new ArrayList<>();

                list.add(Map.of("role", "system", "content", config.string("giga-chat.system", "Ты веселый чел в чате. Развлекай нас.")));
                messageHistories.forEach(mh -> list.add(Map.of("role", mh.role, "content", mh.content)));

                list.add(Map.of("role", "user", "content", message));

                map.put("messages", list);
                map.put("max_tokens", config.integer("giga-chat.max-tokens", 150));
                map.put("temperature", config.realNumber("giga-chat.temperature", 0.7));

                String json = gson.toJson(map);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(config.string("giga-chat.completion-url",
                                "https://gigachat.devices.sberbank.ru/api/v1/chat/completions")))
                        .header("Authorization", "Bearer " + (type == Type.OPENAI ? apiKey : oauthToken.token))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                var resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                response = resp.body();
                JsonResponse jsonResponse = gson.fromJson(resp.body(), JsonResponse.class);

                String mes = jsonResponse.choices.getFirst().message.content;

                if (messageHistories.size() > config.integer("giga-chat.history-size", 30))
                    messageHistories.poll();
                messageHistories.add(new MessageHistory("user", message, System.currentTimeMillis()));
                messageHistories.add(new MessageHistory("assistant", mes, System.currentTimeMillis()));

                return mes;
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println(response);
                return null;
            }
        });
    }

    public Component toAiMessageComponent(String mes) {
        return Utils.legacy(config.string("giga-chat.prefix", "&6%name% &7» ")
                .replace("%name%", config.string("giga-chat.name", "&6ИИ")) + mes);
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
