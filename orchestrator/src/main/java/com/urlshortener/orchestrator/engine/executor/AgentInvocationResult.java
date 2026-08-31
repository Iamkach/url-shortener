package com.urlshortener.orchestrator.engine.executor;

/**
 * Raw outcome of one agent-CLI invocation. {@link AgentNodeExecutor} turns this into a
 * {@link NodeExecutionResult}: a non-zero {@link #exitCode}, {@link #timedOut}, or an unparseable
 * {@link #stdout} all become {@code fail(...)} so the engine's retry/fallback/rollback ladder stays
 * in charge.
 */
public record AgentInvocationResult(int exitCode, String stdout, String stderr, boolean timedOut) {
}
