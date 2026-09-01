package com.urlshortener.orchestrator.engine.executor;

import com.urlshortener.orchestrator.definition.NodeDefinition;
import com.urlshortener.orchestrator.domain.StageType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LlmNodeExecutorTest {

    private NodeExecutionRequest request(String runId) {
        NodeDefinition nd = new NodeDefinition();
        nd.setId("implementation");
        nd.setStage(StageType.IMPLEMENTATION);
        nd.setExitGate("requireArtifact:commit");
        return new NodeExecutionRequest(runId, nd, Map.of("design.designPath", "specs/x/plan.md"));
    }

    private LlmNodeExecutor executor(ChatPort chat) {
        ExecutorProperties props = new ExecutorProperties();
        props.getLlm().setMaxModelCallsPerRun(2);
        return new LlmNodeExecutor(chat, props);
    }

    @Test
    void parsesCompleteResponseAndPassesUpstreamContextToTheModel() {
        AtomicReference<String> userPrompt = new AtomicReference<>();
        ChatPort chat = (system, user, max) -> {
            userPrompt.set(user);
            return "{\"status\":\"complete\",\"artifacts\":{\"commit\":\"deadbee\"},\"notes\":\"done\"}";
        };
        NodeExecutionResult result = executor(chat).execute(request("r1"));

        assertThat(result.outcome()).isEqualTo(NodeExecutionResult.Outcome.COMPLETE);
        assertThat(result.artifacts()).containsEntry("commit", "deadbee");
        assertThat(userPrompt.get()).contains("design.designPath").contains("requireArtifact:commit");
    }

    @Test
    void modelReportedFailureMapsToFail() {
        ChatPort chat = (s, u, m) -> "{\"status\":\"fail\",\"artifacts\":{},\"notes\":\"cannot proceed\"}";
        NodeExecutionResult result = executor(chat).execute(request("r1"));
        assertThat(result.outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(result.notes()).isEqualTo("cannot proceed");
    }

    @Test
    void malformedResponseMapsToFailNotException() {
        ChatPort chat = (s, u, m) -> "I cannot help with that.";
        NodeExecutionResult result = executor(chat).execute(request("r1"));
        assertThat(result.outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(result.notes()).contains("unparseable");
    }

    @Test
    void modelCallExceptionMapsToFail() {
        ChatPort chat = (s, u, m) -> { throw new RuntimeException("429 overloaded"); };
        NodeExecutionResult result = executor(chat).execute(request("r1"));
        assertThat(result.outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(result.notes()).contains("model call failed").contains("429");
    }

    @Test
    void perRunBudgetExhaustionMapsToFail() {
        ChatPort chat = (s, u, m) -> "{\"status\":\"complete\",\"artifacts\":{\"commit\":\"x\"},\"notes\":\"\"}";
        LlmNodeExecutor exec = executor(chat); // budget = 2
        assertThat(exec.execute(request("r1")).outcome()).isEqualTo(NodeExecutionResult.Outcome.COMPLETE);
        assertThat(exec.execute(request("r1")).outcome()).isEqualTo(NodeExecutionResult.Outcome.COMPLETE);
        NodeExecutionResult third = exec.execute(request("r1"));
        assertThat(third.outcome()).isEqualTo(NodeExecutionResult.Outcome.FAIL);
        assertThat(third.notes()).contains("budget exhausted");
    }

    // Reply-parsing specifics (fences, malformed, status/artifact extraction) now live in
    // NodeResultParserTest — shared by the llm and agent executors.
}
