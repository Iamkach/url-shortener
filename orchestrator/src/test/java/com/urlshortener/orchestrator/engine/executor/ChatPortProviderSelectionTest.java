package com.urlshortener.orchestrator.engine.executor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link ChatPortConfig} activates exactly one {@link ChatPort} — the right one — per
 * {@code orchestrator.executor.llm.provider}, and none at all outside {@code mode=llm}. Pure context
 * wiring; {@link AnthropicChatPort} builds its SDK client lazily so no API key is needed here.
 */
class ChatPortProviderSelectionTest {

    @Configuration
    @EnableConfigurationProperties(ExecutorProperties.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, ChatPortConfig.class);

    @Test
    void defaultProviderIsAnthropic() {
        runner.withPropertyValues("orchestrator.executor.mode=llm")
                .run(ctx -> assertThat(ctx).getBean(ChatPort.class).isInstanceOf(AnthropicChatPort.class));
    }

    @Test
    void explicitAnthropicProvider() {
        runner.withPropertyValues(
                        "orchestrator.executor.mode=llm",
                        "orchestrator.executor.llm.provider=anthropic")
                .run(ctx -> assertThat(ctx).getBean(ChatPort.class).isInstanceOf(AnthropicChatPort.class));
    }

    @Test
    void openAiCompatibleProviderSelectsTheOtherPortAndExcludesAnthropic() {
        runner.withPropertyValues(
                        "orchestrator.executor.mode=llm",
                        "orchestrator.executor.llm.provider=openai-compatible")
                .run(ctx -> {
                    assertThat(ctx).getBean(ChatPort.class).isInstanceOf(OpenAiCompatibleChatPort.class);
                    assertThat(ctx).doesNotHaveBean(AnthropicChatPort.class);
                });
    }

    @Test
    void noChatPortOutsideLlmMode() {
        runner.withPropertyValues("orchestrator.executor.mode=manual")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ChatPort.class));
    }
}
