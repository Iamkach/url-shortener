package com.urlshortener.orchestrator.engine.executor;

/**
 * One-method seam over "spawn an agent CLI to do this node's work", mirroring {@link ChatPort}. Keeps
 * {@link AgentNodeExecutor} unit-testable with no subprocess. The only production implementation is
 * {@link ClaudeCliAgentPort}.
 */
public interface AgentInvocationPort {

    AgentInvocationResult invoke(AgentInvocationTask task);
}
