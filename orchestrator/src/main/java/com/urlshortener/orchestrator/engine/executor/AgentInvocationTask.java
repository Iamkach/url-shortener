package com.urlshortener.orchestrator.engine.executor;

import com.urlshortener.orchestrator.domain.StageType;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Everything {@link AgentInvocationPort} needs to spawn an agent CLI as one node's worker. Built by
 * {@link AgentNodeExecutor} from the node's stage + the run's accumulated context; consumed by the
 * production {@link ClaudeCliAgentPort} or a fake in tests.
 *
 * @param prompt       the full stage instruction (real work — read the repo, edit files, run tests)
 * @param allowedTools tool names the agent may use (becomes {@code --allowedTools})
 * @param allowedPaths write globs the agent is confined to for this stage (exported as
 *                     {@code ORCH_ALLOW_PATHS} for the {@code PreToolUse} governance hook)
 */
public record AgentInvocationTask(
        String runId,
        String nodeId,
        StageType stage,
        String prompt,
        List<String> allowedTools,
        List<String> allowedPaths,
        Path workingDir,
        Duration timeout) {
}
