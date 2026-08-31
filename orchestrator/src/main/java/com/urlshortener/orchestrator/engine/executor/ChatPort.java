package com.urlshortener.orchestrator.engine.executor;

/**
 * One-method boundary over the model call so {@link LlmNodeExecutor} is unit-testable without a
 * network. The only production implementation is {@link AnthropicChatPort}.
 */
public interface ChatPort {

    /** @return the model's raw text response (expected to be a JSON object). */
    String complete(String systemPrompt, String userPrompt, int maxOutputTokens);
}
