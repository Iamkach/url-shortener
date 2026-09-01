package com.urlshortener.orchestrator.engine.executor;

import com.urlshortener.orchestrator.definition.NodeDefinition;
import com.urlshortener.orchestrator.domain.StageType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentNodeExecutor} against a fake {@link AgentInvocationPort} — no subprocess, no network.
 * Proves the executor is a thin, well-behaved adapter: it derives the stage's tools/paths, threads
 * upstream context into the prompt, and folds every failure shape into {@code fail(...)}.
 */
class AgentNodeExecutorTest {

    private ExecutorProperties props() {
        ExecutorProperties p = new ExecutorProperties();
        p.setMode("agent");
        p.getAgent().setMaxAgentCallsPerRun(2);
        p.getAgent().setTimeoutSeconds(30);
        p.getAgent().getStagePaths().put("IMPLEMENTATION", "url-shortener-service/src/**, specs/**/tasks.md");
        p.getAgent().getStagePaths().put("DOCUMENTATION", "docs/**,README.md");
        return p;
    }

    private NodeExecutionRequest request(String runId) {
        NodeDefinition nd = new NodeDefinition();
        nd.setId("implementation");
        nd.setStage(StageType.IMPLEMENTATION);
        nd.setExitGate("requireArtifact:commit");
        return new NodeExecutionRequest(runId, nd, Map.of("design.designPath", "specs/004-autonomous-agent/plan.md"));
    }

    private static AgentInvocationPort port(AgentInvocationResult result, AtomicReference<AgentInvocationTask> captured) {
        return task -> {
            captured.set(task);
            return result;
        };
    }

    private static AgentInvocationResult ok(String stdout) {
        return new AgentInvocationResult(0, stdout, "", false);
    }

    @Test
    void parsesCompleteEnvelopeAndThreadsUpstreamContextAndStageDerivation() {
        AtomicReference<AgentInvocationTask> task = new AtomicReference<>();
        String envelope = "{\"type\":\"result\",\"result\":\"{\\\"status\\\":\\\"complete\\\","
                + "\\\"artifacts\\\":{\\\"commit\\\":\\\"abc1234\\\"},\\\"notes\\\":\\\"done\\\"}\"}";
        AgentNodeExecutor exec = new AgentNodeExecutor(port(ok(envelope), task), props());

        NodeExecutionResult r = exec.execute(request("r1"));

        assertThat(r.outcome()).isEqualTo(NodeExecutionResult.Outcome.COMPLETE);
        assertThat(r.artifacts()).containsEntry("commit", "abc1234");
        assertThat(task.get().prompt())
                .contains("design.designPath")
                .contains("specs/004-autonomous-agent/plan.md")
                .contains("requireArtifact:commit");
        assertThat(task.get().allowedTools()).contains("Edit", "Bash");
        assertThat(task.get().allowedPaths()).containsExactly("url-shortener-service/src/**", "specs/**/tasks.md");
        assertThat(task.get().stage()).isEqualTo(StageType.IMPLEMENTATION);
    }

    @Test
    void plainJsonStdoutWithoutEnvelopeIsAlsoParsed() {
        AtomicReference<AgentInvocationTask> task = new AtomicReference<>();
        AgentNodeExecutor exec = new AgentNodeExecutor(
                port(ok("{\"status\":\"complete\",\"artifacts\":{\"commit\":\"x\"},\"notes\":\"\"}"), task), props());
        assertThat(exec.execute(request("r1")).outcome()).isEqualTo(NodeExecutionResult.Outcome.COMPLETE);
    }

    @Test
    void nonZeroExitMapsToFailWithStderr() {
        AtomicReference<AgentInvocationTask> task = new AtomicReference<>();
        AgentInvocationResult res = new AgentInvocationResult(3, "", "boom: mvn test failed", false);
        NodeExecutionResult r = new AgentNodeExecutor(port(res, task), props()).execute(request("r1"));
        assertThat(r.outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(r.notes()).contains("exited 3").contains("boom");
    }

    @Test
    void timeoutMapsToFail() {
        AtomicReference<AgentInvocationTask> task = new AtomicReference<>();
        AgentInvocationResult res = new AgentInvocationResult(-1, "", "", true);
        NodeExecutionResult r = new AgentNodeExecutor(port(res, task), props()).execute(request("r1"));
        assertThat(r.outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(r.notes()).contains("timed out");
    }

    @Test
    void portExceptionMapsToFail() {
        AgentInvocationPort throwing = task -> {
            throw new RuntimeException("cli not found");
        };
        NodeExecutionResult r = new AgentNodeExecutor(throwing, props()).execute(request("r1"));
        assertThat(r.outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(r.notes()).contains("agent invocation failed").contains("cli not found");
    }

    @Test
    void unparseableStdoutMapsToFail() {
        AtomicReference<AgentInvocationTask> task = new AtomicReference<>();
        NodeExecutionResult r = new AgentNodeExecutor(port(ok("I could not finish."), task), props())
                .execute(request("r1"));
        assertThat(r.outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(r.notes()).contains("unparseable");
    }

    @Test
    void perRunBudgetExhaustionMapsToFail() {
        AtomicReference<AgentInvocationTask> task = new AtomicReference<>();
        String good = "{\"status\":\"complete\",\"artifacts\":{\"commit\":\"x\"},\"notes\":\"\"}";
        AgentNodeExecutor exec = new AgentNodeExecutor(port(ok(good), task), props()); // budget = 2
        assertThat(exec.execute(request("r1")).outcome()).isEqualTo(NodeExecutionResult.Outcome.COMPLETE);
        assertThat(exec.execute(request("r1")).outcome()).isEqualTo(NodeExecutionResult.Outcome.COMPLETE);
        NodeExecutionResult third = exec.execute(request("r1"));
        assertThat(third.outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(third.notes()).contains("budget exhausted");
    }

    @Test
    void documentationStageGetsNarrowerToolsAndItsOwnPaths() {
        AtomicReference<AgentInvocationTask> task = new AtomicReference<>();
        NodeDefinition doc = new NodeDefinition();
        doc.setId("documentation");
        doc.setStage(StageType.DOCUMENTATION);
        AgentNodeExecutor exec = new AgentNodeExecutor(
                port(ok("{\"status\":\"complete\",\"artifacts\":{},\"notes\":\"\"}"), task), props());

        exec.execute(new NodeExecutionRequest("r1", doc, Map.of()));

        assertThat(task.get().allowedTools()).doesNotContain("Bash");
        assertThat(task.get().allowedPaths()).containsExactly("docs/**", "README.md");
    }
}
