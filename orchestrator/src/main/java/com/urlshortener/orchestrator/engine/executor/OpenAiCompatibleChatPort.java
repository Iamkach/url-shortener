package com.urlshortener.orchestrator.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link ChatPort} for any OpenAI-compatible {@code /chat/completions} endpoint — local Ollama /
 * LM Studio / vLLM, or a hosted OpenAI-style server. Plain {@code java.net.http.HttpClient}, no new
 * dependency.
 *
 * <p>Wired by {@link ChatPortConfig} only when {@code orchestrator.executor.mode=llm} <em>and</em>
 * {@code orchestrator.executor.llm.provider=openai-compatible}; {@link AnthropicChatPort} owns the
 * default {@code anthropic} provider. Exactly one {@code ChatPort} bean is ever active.
 */
@Slf4j
public class OpenAiCompatibleChatPort implements ChatPort {

    private final ExecutorProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json = new ObjectMapper();

    public OpenAiCompatibleChatPort(ExecutorProperties properties) {
        this.properties = properties;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, int maxOutputTokens) {
        ExecutorProperties.Llm cfg = properties.getLlm();
        String baseUrl = cfg.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "orchestrator.executor.llm.base-url is required for provider=openai-compatible");
        }
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        ObjectNode body = json.createObjectNode();
        body.put("model", cfg.getModel());
        body.put("max_tokens", maxOutputTokens);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);

        HttpRequest.Builder req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("could not build chat request: " + e.getMessage(), e);
        }

        String key = cfg.getApiKeyEnv() == null ? null : System.getenv(cfg.getApiKeyEnv());
        if (key != null && !key.isBlank()) {
            req.header("Authorization", "Bearer " + key);
        }

        HttpResponse<String> response;
        try {
            response = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("chat request to " + url + " failed: " + e.getMessage(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("chat endpoint " + url + " returned HTTP " + response.statusCode()
                    + ": " + truncate(response.body()));
        }
        try {
            JsonNode root = json.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            throw new RuntimeException("unparseable chat response: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        s = s == null ? "" : s.strip();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
