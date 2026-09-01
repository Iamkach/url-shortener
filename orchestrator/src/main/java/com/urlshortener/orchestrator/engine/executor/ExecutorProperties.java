package com.urlshortener.orchestrator.engine.executor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds {@code orchestrator.executor.*}. {@code mode} is the global default executor for nodes that
 * don't name their own; a node's {@code executor:} in the workflow YAML overrides it.
 */
@Data
@ConfigurationProperties(prefix = "orchestrator.executor")
public class ExecutorProperties {

    /** {@code manual} (default) | {@code scripted} | {@code llm} | {@code agent}. */
    private String mode = ManualNodeExecutor.ID;

    private Llm llm = new Llm();

    private Agent agent = new Agent();

    @Data
    public static class Llm {
        /** {@code anthropic} (default) | {@code openai-compatible}. Selects the active {@code ChatPort}. */
        private String provider = "anthropic";
        /** Base URL for {@code openai-compatible} (e.g. {@code http://localhost:11434/v1}); ignored for anthropic. */
        private String baseUrl = "";
        /** Env var holding the API key/bearer token for the selected provider. */
        private String apiKeyEnv = "ANTHROPIC_API_KEY";
        /** Set to {@code claude-sonnet-5} for a cheaper demo run. */
        private String model = "claude-opus-5";
        private int maxModelCallsPerRun = 12;
        private int maxOutputTokens = 4096;
    }

    /**
     * Config for the {@code agent} executor — spawns an agent CLI (Claude Code by default) as the
     * node's worker. Agent-agnostic: {@code command} + {@code argsTemplate} are swappable.
     */
    @Data
    public static class Agent {
        /** Agent CLI executable. Default {@code claude}; set {@code ORCH_AGENT_CMD} to swap (e.g. {@code codex}). */
        private String command = "claude";
        /**
         * Space-separated arg template. Placeholders: {@code {repoDir}}, {@code {allowedTools}}.
         * Default drives Claude Code headless with JSON output.
         */
        private String argsTemplate =
                "-p --output-format json --permission-mode acceptEdits --add-dir {repoDir} --allowedTools {allowedTools}";
        /** {@code stdin} (default) | {@code arg} — how the stage prompt reaches the CLI. */
        private String promptVia = "stdin";
        /** Working directory for the spawned process, relative to the orchestrator module. */
        private String workingDir = "..";
        private int timeoutSeconds = 900;
        private int maxAgentCallsPerRun = 12;
        /** Stage name (upper-case {@code StageType}) → comma-separated write globs the agent is allowed. */
        private Map<String, String> stagePaths = new LinkedHashMap<>();
    }
}
