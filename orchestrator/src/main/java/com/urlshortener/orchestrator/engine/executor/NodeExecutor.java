package com.urlshortener.orchestrator.engine.executor;

/**
 * The seam between the orchestration engine and whoever actually does a node's SDLC work.
 *
 * <p>The engine dispatches a node to {@code RUNNING} and — for a non-{@code manual} executor on an
 * autonomous run — {@link NodeDispatchListener} hands the node here, then feeds the
 * {@link NodeExecutionResult} back through {@code WorkflowEngine.complete}/{@code fail}. Governance
 * (entry/exit gates, human approval gates, bounded retry, fallback, rollback, audit, metrics) is
 * identical whether the work came from an executor or from an external REST callback — an executor
 * is just an automated stand-in for the human/agent that used to POST back.
 */
public interface NodeExecutor {

    /** Stable id matched against {@code NodeDefinition.executor} / {@code orchestrator.executor.mode}. */
    String id();

    NodeExecutionResult execute(NodeExecutionRequest request);
}
