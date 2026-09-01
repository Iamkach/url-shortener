package com.urlshortener.orchestrator.engine.executor;

import com.urlshortener.orchestrator.definition.NodeDefinition;

import java.util.Map;

/**
 * Everything a {@link NodeExecutor} needs to do one node's SDLC work: the static node definition
 * (stage, gates) plus a read-only snapshot of the run's accumulated, namespaced context
 * ({@code nodeId.artifactKey}) so the executor can see upstream artifacts.
 */
public record NodeExecutionRequest(String runId, NodeDefinition node, Map<String, String> context) {
}
