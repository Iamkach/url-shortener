package com.urlshortener.orchestrator.engine.executor;

import com.urlshortener.orchestrator.definition.NodeDefinition;
import com.urlshortener.orchestrator.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeExecutorRegistryTest {

    private final ManualNodeExecutor manual = new ManualNodeExecutor();
    private final ScriptedNodeExecutor scripted = new ScriptedNodeExecutor();
    private final NodeExecutor agent = new NodeExecutor() {
        @Override public String id() {
            return AgentNodeExecutor.ID;
        }
        @Override public NodeExecutionResult execute(NodeExecutionRequest request) {
            return NodeExecutionResult.fail("stub");
        }
    };

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

    @Test
    void agentExecutorResolvesWhenItsBeanIsPresent() {
        ExecutorProperties props = new ExecutorProperties();
        props.setMode("manual");
        NodeExecutorRegistry reg = new NodeExecutorRegistry(List.of(manual, agent), props);
        assertThat(reg.resolve(node("agent"))).isSameAs(agent);
        assertThat(reg.isManual(node("agent"))).isFalse();
    }

    @Test
    void agentIsAcceptedByWorkflowValidation() {
        WorkflowDefinition def = new WorkflowDefinition("wf", "ok", List.of(node("agent")));
        assertThatCode(def::validate).doesNotThrowAnyException();
    }
}
