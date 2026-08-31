package com.urlshortener.orchestrator.engine.executor;

import com.urlshortener.orchestrator.definition.NodeDefinition;
import com.urlshortener.orchestrator.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeExecutorRegistryTest {

    private final ManualNodeExecutor manual = new ManualNodeExecutor();
    private final ScriptedNodeExecutor scripted = new ScriptedNodeExecutor();

    private NodeExecutorRegistry registry(String globalMode) {
        ExecutorProperties props = new ExecutorProperties();
        props.setMode(globalMode);
        return new NodeExecutorRegistry(List.of(manual, scripted), props);
    }

    private NodeDefinition node(String executor) {
        NodeDefinition nd = new NodeDefinition();
        nd.setId("n");
        nd.setExecutor(executor);
        return nd;
    }

    @Test
    void nodeLevelExecutorOverridesGlobalMode() {
        assertThat(registry("manual").resolve(node("scripted"))).isSameAs(scripted);
        assertThat(registry("scripted").resolve(node("manual"))).isSameAs(manual);
    }

    @Test
    void fallsBackToManualWhenRequestedExecutorHasNoBean() {
        assertThat(registry("llm").resolve(node(null)).id()).isEqualTo(ManualNodeExecutor.ID);
    }

    @Test
    void globalModeUsedWhenNodeHasNoExecutor() {
        assertThat(registry("scripted").resolve(node(null))).isSameAs(scripted);
        assertThat(registry("scripted").isManual(node(null))).isFalse();
        assertThat(registry("manual").isManual(node("  "))).isTrue();
    }

    @Test
    void workflowValidationRejectsUnknownExecutor() {
        NodeDefinition bad = node("gpt5");
        WorkflowDefinition def = new WorkflowDefinition("wf", "bad", List.of(bad));
        assertThatThrownBy(def::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown executor");
    }
}
