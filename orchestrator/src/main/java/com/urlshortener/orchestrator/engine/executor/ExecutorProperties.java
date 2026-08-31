package com.urlshortener.orchestrator.engine.executor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code orchestrator.executor.*}. {@code mode} is the global default executor for nodes that
 * don't name their own; a node's {@code executor:} in the workflow YAML overrides it.
 */
@Data
@ConfigurationProperties(prefix = "orchestrator.executor")
public class ExecutorProperties {

    /** {@code manual} (default) | {@code scripted} | {@code llm}. */
    private String mode = ManualNodeExecutor.ID;

    private Llm llm = new Llm();

    @Data
    public static class Llm {
        /** Set to {@code claude-sonnet-5} for a cheaper demo run. */
        private String model = "claude-opus-5";
        private int maxModelCallsPerRun = 12;
        private int maxOutputTokens = 4096;
    }
}
