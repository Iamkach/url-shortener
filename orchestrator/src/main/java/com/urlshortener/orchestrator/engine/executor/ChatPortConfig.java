package com.urlshortener.orchestrator.engine.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the single active {@link ChatPort} for the {@code llm} executor by
 * {@code orchestrator.executor.llm.provider}. The whole config is gated on {@code mode=llm}, so
 * neither implementation is instantiated in the default / {@code manual} / {@code agent} boots.
 *
 * <p>{@code anthropic} is the default ({@code matchIfMissing=true}); {@code openai-compatible}
 * routes to any OpenAI-style {@code /chat/completions} server. The two {@code havingValue}s are
 * mutually exclusive, so exactly one bean is created.
 */
@Configuration
@ConditionalOnProperty(prefix = "orchestrator.executor", name = "mode", havingValue = "llm")
public class ChatPortConfig {

    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.executor.llm", name = "provider",
            havingValue = "anthropic", matchIfMissing = true)
    public ChatPort anthropicChatPort(ExecutorProperties properties) {
        return new AnthropicChatPort(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchestrator.executor.llm", name = "provider",
            havingValue = "openai-compatible")
    public ChatPort openAiCompatibleChatPort(ExecutorProperties properties) {
        return new OpenAiCompatibleChatPort(properties);
    }
}
