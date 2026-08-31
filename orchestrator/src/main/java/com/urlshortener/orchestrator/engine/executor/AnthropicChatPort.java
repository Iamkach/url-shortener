package com.urlshortener.orchestrator.engine.executor;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Real Anthropic Messages API call. Only loaded when {@code orchestrator.executor.mode=llm}, so the
 * app boots fine (and every test runs) without an {@code ANTHROPIC_API_KEY} in the default mode.
 */
@Component
@ConditionalOnProperty(prefix = "orchestrator.executor", name = "mode", havingValue = "llm")
@Slf4j
public class AnthropicChatPort implements ChatPort {

    private final AnthropicClient client;
    private final ExecutorProperties properties;

    public AnthropicChatPort(ExecutorProperties properties) {
        this.properties = properties;
        this.client = AnthropicOkHttpClient.fromEnv(); // reads ANTHROPIC_API_KEY / ant profile
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
        Message response = client.messages().create(params);
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .collect(Collectors.joining());
    }
}
