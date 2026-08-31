package com.urlshortener.orchestrator.engine.executor;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic {@link NodeExecutor} for tests and offline demos: canned artifacts per node id, with
 * optional "fail the first attempt" support so the retry ladder can be exercised without a network.
 */
public class ScriptedNodeExecutor implements NodeExecutor {

    public static final String ID = "scripted";

    private final Map<String, Map<String, String>> artifactsByNode = new ConcurrentHashMap<>();
    private final Set<String> failFirstAttempt = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    public ScriptedNodeExecutor complete(String nodeId, Map<String, String> artifacts) {
        artifactsByNode.put(nodeId, artifacts);
        return this;
    }

    /** Node fails once (retryable), then completes with the given artifacts. */
    public ScriptedNodeExecutor failFirstThenComplete(String nodeId, Map<String, String> artifacts) {
        failFirstAttempt.add(nodeId);
        return complete(nodeId, artifacts);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionRequest request) {
        String nodeId = request.node().getId();
        int attempt = attempts.merge(nodeId, 1, Integer::sum);
        if (failFirstAttempt.contains(nodeId) && attempt == 1) {
            return NodeExecutionResult.fail("scripted transient failure on first attempt");
        }
        Map<String, String> artifacts = artifactsByNode.get(nodeId);
        if (artifacts == null) {
            return NodeExecutionResult.fail("no script configured for node '" + nodeId + "'");
        }
        return NodeExecutionResult.complete(artifacts, "scripted");
    }
}
