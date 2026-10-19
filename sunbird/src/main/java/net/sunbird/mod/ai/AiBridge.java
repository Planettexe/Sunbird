package net.sunbird.mod.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.sunbird.mod.SunbirdConfig;
import net.sunbird.mod.SunbirdMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Talks to any OpenAI-compatible server:
 *   - llama.cpp server  (llama-server -m model.gguf --port 8080)   -> http://host:8080/v1
 *   - ollama            (OLLAMA_HOST=... ollama serve + /api alias)-> http://host:11434/v1
 *   - LM Studio, vLLM, etc.
 *
 * Requests run on the JDK HTTP client's async thread pool, never on the server tick loop.
 */
public final class AiBridge {
    private static SunbirdConfig cfg;
    private static HttpClient http;

    private AiBridge() {}

    public static void init(SunbirdConfig config) {
        cfg = config;
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static String describe() {
        if (cfg == null) return "uninitialized";
        return cfg.apiBaseUrl + " (model=" + cfg.model + ", style=" + cfg.endpointStyle + ")";
    }

    public record Turn(String role, String content) {}

    /** Fire and forget with a callback delivered on an async thread. */
    public static CompletableFuture<String> ask(List<Turn> conversation) {
        return switch (cfg.endpointStyle.toLowerCase()) {
            case "completions" -> askRawCompletion(conversation);
            default -> askChat(conversation);
        };
    }

    // ---------------- OpenAI /chat/completions ----------------
    private static CompletableFuture<String> askChat(List<Turn> conversation) {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("temperature", cfg.temperature);
        body.addProperty("top_p", cfg.topP);
        body.addProperty("max_tokens", cfg.maxTokens);
        body.addProperty("stream", false);

        JsonArray messages = new JsonArray();
        for (Turn t : conversation) {
            JsonObject m = new JsonObject();
            m.addProperty("role", t.role());
            m.addProperty("content", t.content());
            messages.add(m);
        }
        body.add("messages", messages);

        HttpRequest req = baseRequest()
                .uri(URI.create(trim(cfg.apiBaseUrl) + "/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return send(req).thenApply(AiBridge::extractChatContent);
    }

    // ---------------- raw /completions (llama.cpp simple proxy style) ----------------
    private static CompletableFuture<String> askRawCompletion(List<Turn> conversation) {
        StringBuilder prompt = new StringBuilder();
        for (Turn t : conversation) {
            prompt.append(t.role()).append('\n').append(t.content()).append("\n\n");
        }
        prompt.append("assistant\n");

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("prompt", prompt.toString());
        body.addProperty("temperature", cfg.temperature);
        body.addProperty("top_p", cfg.topP);
        body.addProperty("max_tokens", cfg.maxTokens);
        body.addProperty("stream", false);

        HttpRequest req = baseRequest()
                .uri(URI.create(trim(cfg.apiBaseUrl) + "/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return send(req).thenApply(json -> {
            try {
                return json.getAsJsonObject().get("choices").getAsJsonArray()
                        .get(0).getAsJsonObject().get("text").getAsString().trim();
            } catch (Exception e) {
                return "";
            }
        });
    }

    // ---------------- plumbing ----------------
    private static HttpRequest.Builder baseRequest() {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(cfg.requestTimeoutSeconds));
        if (cfg.apiKey != null && !cfg.apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + cfg.apiKey);
        }
        return b;
    }

    private static CompletableFuture<JsonObject> send(HttpRequest req) {
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        SunbirdMod.LOGGER.warn("[Sunbird] AI backend HTTP {}: {}", resp.statusCode(), resp.body());
                        return new JsonObject();
                    }
                    try {
                        return com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
                    } catch (Exception e) {
                        return new JsonObject();
                    }
                })
                .exceptionally(ex -> {
                    SunbirdMod.LOGGER.warn("[Sunbird] AI backend unreachable ({}): {}",
                            cfg.apiBaseUrl, ex.getMessage());
                    return new JsonObject();
                });
    }

    private static String extractChatContent(JsonObject json) {
        try {
            return json.get("choices").getAsJsonArray().get(0).getAsJsonObject()
                    .get("message").getAsJsonObject().get("content").getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Health probe used by /aiconfig test. */
    public static CompletableFuture<Map<Boolean, String>> healthCheck() {
        HttpRequest req = baseRequest()
                .uri(URI.create(trim(cfg.apiBaseUrl) + "/models"))
                .GET()
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> Map.of(r.statusCode() == 200,
                        "HTTP " + r.statusCode() + " from " + trim(cfg.apiBaseUrl) + "/models"))
                .exceptionally(ex -> Map.of(false, "Unreachable: " + ex.getMessage()));
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
