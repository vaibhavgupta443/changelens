package com.changelens.ask;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to any server that speaks the OpenAI chat-completions protocol: OpenRouter, OpenAI, a local Ollama ({@code http://localhost:11434/v1}),
 * LM Studio, vLLM. ChangeLens does not pick a vendor and never sends anything anywhere the caller did not point it at.
 *
 * <p>One request per question, temperature 0, and a cap on the reply length, so the cost of a question is small and known in advance.
 */
public final class OpenAiCompatibleModel implements ChatModel {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI endpoint;
    private final String key;
    private final String model;
    private final int maxReplyTokens;
    private final Duration timeout;

    /**
     * @param baseUrl the API root, for example {@code https://openrouter.ai/api/v1}; {@code /chat/completions} is appended
     * @param key     the API key; may be blank for a local server that needs none
     */
    public OpenAiCompatibleModel(String baseUrl, String key, String model, int maxReplyTokens, Duration timeout) {
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(root + "/chat/completions");
        this.key = key == null ? "" : key;
        this.model = model;
        this.maxReplyTokens = maxReplyTokens;
        this.timeout = timeout;
    }

    /**
     * Reads {@code CHANGELENS_LLM_URL}, {@code CHANGELENS_LLM_MODEL} and {@code CHANGELENS_LLM_KEY}.
     *
     * @throws ModelException naming what is missing
     */
    public static OpenAiCompatibleModel fromEnvironment() {
        String url = System.getenv("CHANGELENS_LLM_URL");
        String model = System.getenv("CHANGELENS_LLM_MODEL");
        if (url == null || url.isBlank() || model == null || model.isBlank()) {
            throw new ModelException("Set CHANGELENS_LLM_URL (for example https://openrouter.ai/api/v1 or http://localhost:11434/v1) "
                    + "and CHANGELENS_LLM_MODEL, and CHANGELENS_LLM_KEY if the server needs a key.");
        }
        return new OpenAiCompatibleModel(url, System.getenv("CHANGELENS_LLM_KEY"), model, 1500, Duration.ofSeconds(90));
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public String complete(String system, String user) {
        String body = JSON.writeValueAsString(Map.of(
                "model", model,
                "temperature", 0,
                "max_tokens", maxReplyTokens,
                "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user))));
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (!key.isBlank()) {
            request.header("Authorization", "Bearer " + key);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ModelException("Could not reach " + endpoint.getHost() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("Interrupted while waiting for the model", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ModelException("The model server answered HTTP " + response.statusCode() + ": " + excerpt(response.body()));
        }
        String text;
        try {
            JsonNode reply = JSON.readTree(response.body());
            text = reply.path("choices").path(0).path("message").path("content").asString("");
        } catch (RuntimeException e) {
            throw new ModelException("The model server did not return JSON: " + excerpt(response.body()), e);
        }
        if (text.isBlank()) {
            throw new ModelException("The model returned no text: " + excerpt(response.body()));
        }
        return text;
    }

    private static String excerpt(String s) {
        String flat = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }
}
