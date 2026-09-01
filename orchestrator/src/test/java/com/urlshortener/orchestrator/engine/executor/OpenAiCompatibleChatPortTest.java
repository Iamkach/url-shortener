package com.urlshortener.orchestrator.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OpenAiCompatibleChatPort} against a real in-process {@code /chat/completions} stub — no
 * network, no external service. Asserts the request shape, the bearer token from the configured env
 * var, and that {@code choices[0].message.content} is what comes back.
 */
class OpenAiCompatibleChatPortTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi from stub\"}}]}";

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    /** {@code PATH} exists on every OS the build runs on — a stable stand-in for a real key var. */
    private ExecutorProperties props() {
        ExecutorProperties p = new ExecutorProperties();
        p.setMode("llm");
        p.getLlm().setProvider("openai-compatible");
        p.getLlm().setModel("local-model");
        p.getLlm().setApiKeyEnv("PATH");
        p.getLlm().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        return p;
    }

    @Test
    void sendsOpenAiShapedRequestAndReadsChoicesContent() throws Exception {
        String reply = new OpenAiCompatibleChatPort(props()).complete("be terse", "say hi", 256);

        assertThat(reply).isEqualTo("hi from stub");
        JsonNode body = json.readTree(capturedBody.get());
        assertThat(body.get("model").asText()).isEqualTo("local-model");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(256);
        assertThat(body.get("messages")).hasSize(2);
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("be terse");
        assertThat(body.get("messages").get(1).get("role").asText()).isEqualTo("user");
        assertThat(body.get("messages").get(1).get("content").asText()).isEqualTo("say hi");
    }

    @Test
    void sendsBearerTokenFromConfiguredEnvVar() {
        new OpenAiCompatibleChatPort(props()).complete("s", "u", 16);
        assertThat(capturedAuth.get()).isEqualTo("Bearer " + System.getenv("PATH"));
    }

    @Test
    void nonZeroHttpStatusBecomesRuntimeException() {
        status = 500;
        responseBody = "upstream boom";
        assertThatThrownBy(() -> new OpenAiCompatibleChatPort(props()).complete("s", "u", 16))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void missingBaseUrlIsRejected() {
        ExecutorProperties p = props();
        p.getLlm().setBaseUrl("  ");
        assertThatThrownBy(() -> new OpenAiCompatibleChatPort(p).complete("s", "u", 16))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }
}
