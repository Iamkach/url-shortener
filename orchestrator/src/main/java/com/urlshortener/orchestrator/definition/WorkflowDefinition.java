package com.urlshortener.orchestrator.definition;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Static, immutable-at-runtime DAG of {@link NodeDefinition}s loaded from YAML. Represents the
 * "explicit dependency graph with entry/exit gates" required by the assignment: one template can
 * be re-used across many {@code WorkflowRunEntity} executions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDefinition {

    private String id;
    private String name;
    private List<NodeDefinition> nodes = new ArrayList<>();

    public Optional<NodeDefinition> node(String nodeId) {
        return nodes.stream().filter(n -> n.getId().equals(nodeId)).findFirst();
    }

    /** Node ids that directly depend on the given node. */
    public List<String> directDependents(String nodeId) {
        List<String> out = new ArrayList<>();
        for (NodeDefinition n : nodes) {
            if (n.getDependsOn().contains(nodeId)) {
                out.add(n.getId());
            }
        }
        return out;
    }

    /** All node ids transitively downstream of the given node (BFS over directDependents). */
    public Set<String> transitiveDownstream(String nodeId) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(directDependents(nodeId));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.add(current)) {
                queue.addAll(directDependents(current));
            }
        }
        return visited;
    }

    /**
     * Validates the graph is a true DAG (no cycles, no dangling dependsOn references) via
     * Kahn's algorithm. Throws {@link IllegalStateException} describing the problem if invalid.
     * Run once at load time so a malformed workflow can never be registered.
     */
    public void validate() {
        Map<String, NodeDefinition> byId = new HashMap<>();
        for (NodeDefinition n : nodes) {
            if (byId.putIfAbsent(n.getId(), n) != null) {
                throw new IllegalStateException("Workflow '" + id + "' has duplicate node id: " + n.getId());
            }
        }
        for (NodeDefinition n : nodes) {
            for (String dep : n.getDependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new IllegalStateException("Workflow '" + id + "' node '" + n.getId()
                            + "' depends on unknown node '" + dep + "'");
                }
            }
            if (n.getFallbackNodeId() != null && !byId.containsKey(n.getFallbackNodeId())) {
                throw new IllegalStateException("Workflow '" + id + "' node '" + n.getId()
                        + "' has unknown fallbackNodeId '" + n.getFallbackNodeId() + "'");
            }
        }

        Map<String, Integer> inDegree = new HashMap<>();
        for (NodeDefinition n : nodes) {
            inDegree.put(n.getId(), n.getDependsOn().size());
        }
        Deque<String> ready = new ArrayDeque<>();
        inDegree.forEach((id, deg) -> {
            if (deg == 0) {
                ready.add(id);
            }
        });
        int visitedCount = 0;
        while (!ready.isEmpty()) {
            String current = ready.poll();
            visitedCount++;
            for (String dependent : directDependents(current)) {
                int newDeg = inDegree.merge(dependent, -1, Integer::sum);
                if (newDeg == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (visitedCount != nodes.size()) {
            throw new IllegalStateException("Workflow '" + id + "' contains a cycle among its nodes");
        }
    }
}
