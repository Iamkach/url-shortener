package com.urlshortener.orchestrator.engine.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production {@link AgentInvocationPort}: renders an agent-CLI process from
 * {@link ExecutorProperties.Agent} and runs it via {@link ProcessRunner}. Agent-agnostic — the
 * default command is Claude Code headless ({@code claude -p --output-format json …}) but
 * {@code command} + {@code argsTemplate} are config, so {@code codex} or a local agent drop in
 * unchanged.
 *
 * <p>Loaded only when {@code orchestrator.executor.mode=agent}.
 */
@Component
@ConditionalOnProperty(prefix = "orchestrator.executor", name = "mode", havingValue = "agent")
@Slf4j
public class ClaudeCliAgentPort implements AgentInvocationPort {

    private final ExecutorProperties properties;
    private final ProcessRunner runner;

    public ClaudeCliAgentPort(ExecutorProperties properties, ProcessRunner runner) {
        this.properties = properties;
        this.runner = runner;
    }

    @Override
    public AgentInvocationResult invoke(AgentInvocationTask task) {
        ExecutorProperties.Agent cfg = properties.getAgent();
        String repoDir = task.workingDir() == null ? "." : task.workingDir().toString();
        String allowedTools = String.join(",", task.allowedTools());

        List<String> command = new ArrayList<>();
        command.add(cfg.getCommand());
        for (String token : cfg.getArgsTemplate().trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            command.add(token.replace("{repoDir}", repoDir).replace("{allowedTools}", allowedTools));
        }

        String stdin = null;
        if ("arg".equalsIgnoreCase(cfg.getPromptVia())) {
            command.add(task.prompt());
        } else {
            stdin = task.prompt();
        }

        Map<String, String> env = new LinkedHashMap<>();
        env.put("ORCH_RUN_ID", task.runId());
        env.put("ORCH_NODE_ID", task.nodeId());
        env.put("ORCH_NODE_STAGE", task.stage().name());
        env.put("ORCH_ALLOW_PATHS", String.join(",", task.allowedPaths()));

        ProcessRunner.Outcome o = runner.run(command, task.workingDir(), env, stdin, task.timeout());
        if (o.timedOut()) {
            log.warn("agent for node '{}' timed out", task.nodeId());
        } else if (o.exitCode() != 0) {
            log.warn("agent for node '{}' exited {}", task.nodeId(), o.exitCode());
        }
        return new AgentInvocationResult(o.exitCode(), o.stdout(), o.stderr(), o.timedOut());
    }
}
