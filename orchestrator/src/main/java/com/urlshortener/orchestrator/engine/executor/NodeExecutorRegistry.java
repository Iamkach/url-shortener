package com.urlshortener.orchestrator.engine.executor;

import com.urlshortener.orchestrator.definition.NodeDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves which {@link NodeExecutor} runs a given node: the node's own {@code executor:} if set,
 * otherwise the global {@code orchestrator.executor.mode}. Falls back to {@code manual} (with a
 * warning) if the requested executor has no bean — e.g. {@code llm} requested but the app started
 * without the LLM executor on the classpath/profile.
 */
@Component
@Slf4j
public class NodeExecutorRegistry {

    private final Map<String, NodeExecutor> byId;
    private final ExecutorProperties properties;

    public NodeExecutorRegistry(List<NodeExecutor> executors, ExecutorProperties properties) {
        this.byId = executors.stream().collect(Collectors.toMap(NodeExecutor::id, Function.identity()));
        this.properties = properties;
    }

    /** The id that would be used for this node, before bean resolution. */
    public String requestedId(NodeDefinition node) {
        String nodeLevel = node.getExecutor();
        return nodeLevel != null && !nodeLevel.isBlank() ? nodeLevel.trim() : properties.getMode();
    }

    public boolean isManual(NodeDefinition node) {
        return resolve(node).id().equals(ManualNodeExecutor.ID);
    }

    public NodeExecutor resolve(NodeDefinition node) {
        String id = requestedId(node);
        NodeExecutor executor = byId.get(id);
        if (executor == null) {
            log.warn("No NodeExecutor bean for id '{}' (node '{}'); falling back to manual", id, node.getId());
            return byId.get(ManualNodeExecutor.ID);
        }
        return executor;
    }
}
