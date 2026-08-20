package com.urlshortener.orchestrator.definition;

import com.urlshortener.orchestrator.domain.StageType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowDefinitionTest {

    private NodeDefinition node(String id, List<String> deps) {
        NodeDefinition n = new NodeDefinition();
        n.setId(id);
        n.setStage(StageType.IMPLEMENTATION);
        n.setDependsOn(deps);
        return n;
    }

    @Test
    void validate_acceptsAcyclicGraph() {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId("wf");
        def.setNodes(List.of(node("a", List.of()), node("b", List.of("a")), node("c", List.of("a", "b"))));

        def.validate();
    }

    @Test
    void validate_rejectsCycle() {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId("wf");
        def.setNodes(List.of(node("a", List.of("b")), node("b", List.of("a"))));

        assertThatThrownBy(def::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("cycle");
    }

    @Test
    void validate_rejectsDanglingDependency() {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId("wf");
        def.setNodes(List.of(node("a", List.of("ghost"))));

        assertThatThrownBy(def::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown node");
    }

    @Test
    void transitiveDownstream_returnsAllReachableDependents() {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId("wf");
        def.setNodes(List.of(
                node("a", List.of()),
                node("b", List.of("a")),
                node("c", List.of("b")),
                node("d", List.of("a"))));

        assertThat(def.transitiveDownstream("a")).containsExactlyInAnyOrder("b", "c", "d");
    }
}
