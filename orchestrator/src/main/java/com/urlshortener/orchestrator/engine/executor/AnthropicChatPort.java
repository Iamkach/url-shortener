package com.urlshortener.orchestrator.engine.executor;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import lombok.extern.slf4j.Slf4j;

import java.util.stream.Collectors;

/**
 * Real Anthropic Messages API call — the default {@link ChatPort} provider. Wired by
 * {@link ChatPortConfig} only when {@code orchestrator.executor.mode=llm} and
 * {@code orchestrator.executor.llm.provider=anthropic} (the default), so the app boots (and every
 * test runs) without an {@code ANTHROPIC_API_KEY} in any other mode.
 *
 * <p>The SDK client is built lazily on first use, not in the constructor, so bean wiring / provider
 * selection never needs a key.
 */
@Slf4j
public class AnthropicChatPort implements ChatPort {

    private final ExecutorProperties properties;
    private volatile AnthropicClient client;

    public AnthropicChatPort(ExecutorProperties properties) {
        this.properties = properties;
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = AnthropicOkHttpClient.fromEnv(); // reads ANTHROPIC_API_KEY / ant profile
                    client = c;
                }
            }
        }
        return c;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, int maxOutputTokens) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.getLlm().getModel())
                .maxTokens((long) maxOutputTokens)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(systemPrompt)
                .addUserMessage(userPrompt)
                .build();
        Message response = client().messages().create(params);
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .collect(Collectors.joining());
    }
}
