package com.urlshortener.orchestrator.engine.executor;

/**
 * Published by {@code WorkflowEngine.dispatchNode} whenever a node enters {@code RUNNING}. Consumed
 * after the surrounding transaction commits by {@link NodeDispatchListener}, which decides whether
 * an executor should pick the node up.
 */
public record NodeDispatchedEvent(String runId, String nodeId) {
}
