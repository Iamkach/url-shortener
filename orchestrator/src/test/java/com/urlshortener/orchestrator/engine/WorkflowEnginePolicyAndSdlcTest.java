package com.urlshortener.orchestrator.engine;

import com.urlshortener.orchestrator.domain.EventType;
import com.urlshortener.orchestrator.domain.RunStatus;
import com.urlshortener.orchestrator.domain.WorkflowRunEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkflowEnginePolicyAndSdlcTest {

    @Autowired
    private WorkflowEngine engine;

    private String nodeStatus(String runId, String nodeId) {
        return engine.getNodes(runId).stream()
                .filter(n -> n.getNodeId().equals(nodeId))
                .findFirst().orElseThrow().getStatus().name();
    }

    @Test
    void entryGateDenial_failsNodeImmediatelyWithoutDispatch() {
        WorkflowRunEntity run = engine.startRun("test-gate", Map.of("blockNode", "true"), "tester");
        String runId = run.getId();

        assertThat(nodeStatus(runId, "only")).isEqualTo("FAILED");
        assertThat(engine.getRun(runId).getStatus()).isEqualTo(RunStatus.FAILED);
        List<EventType> eventTypes = engine.getAudit(runId).stream().map(e -> e.getEventType()).toList();
        assertThat(eventTypes).contains(EventType.POLICY_VIOLATION);
    }

    @Test
    void entryGateAllows_whenContextDoesNotMatchDenyCondition() {
        WorkflowRunEntity run = engine.startRun("test-gate", Map.of("blockNode", "false"), "tester");
        String runId = run.getId();

        assertThat(nodeStatus(runId, "only")).isEqualTo("RUNNING");
    }

    @Test
    void exitGateDenial_missingRequiredArtifact_failsNodeAndRollsBackNothingWhenNoCompensableWork() {
        WorkflowRunEntity run = engine.startRun("sdlc-standard", Map.of(), "tester");
        String runId = run.getId();

        assertThat(nodeStatus(runId, "requirements")).isEqualTo("AWAITING_APPROVAL");
        // Approve without supplying the specPath artifact the exit gate requires.
        engine.approve(runId, "requirements", "reviewer1", "approved conceptually", Map.of());

        assertThat(nodeStatus(runId, "requirements")).isEqualTo("FAILED");
        assertThat(engine.getRun(runId).getStatus()).isEqualTo(RunStatus.FAILED);
        List<EventType> eventTypes = engine.getAudit(runId).stream().map(e -> e.getEventType()).toList();
        assertThat(eventTypes).contains(EventType.POLICY_VIOLATION);
    }

    @Test
    void fullSdlcStandardHappyPath_propagatesNamespacedContextAcrossStages() {
        WorkflowRunEntity run = engine.startRun("sdlc-standard", Map.of(), "product-owner");
        String runId = run.getId();

        assertThat(nodeStatus(runId, "requirements")).isEqualTo("AWAITING_APPROVAL");
        engine.approve(runId, "requirements", "product-owner", "requirements clear",
                Map.of("specPath", "specs/001-core-url-shortener/spec.md"));

        assertThat(nodeStatus(runId, "design")).isEqualTo("RUNNING");
        engine.complete(runId, "design", Map.of("designPath", "specs/001-core-url-shortener/plan.md"), "agent");

        assertThat(nodeStatus(runId, "implementation")).isEqualTo("RUNNING");
        engine.complete(runId, "implementation", Map.of("commit", "abc1234"), "agent");

        // Both TESTING and DOCUMENTATION depend only on implementation -> parallel dispatch.
        assertThat(nodeStatus(runId, "testing")).isEqualTo("RUNNING");
        assertThat(nodeStatus(runId, "documentation")).isEqualTo("RUNNING");

        engine.complete(runId, "testing", Map.of("testReport", "target/surefire-reports"), "agent");
        engine.complete(runId, "documentation", Map.of("docsPath", "docs/architecture.md"), "agent");

        assertThat(nodeStatus(runId, "release_readiness")).isEqualTo("AWAITING_APPROVAL");
        engine.approve(runId, "release_readiness", "release-manager", "ready to ship", Map.of());

        WorkflowRunEntity finalRun = engine.getRun(runId);
        assertThat(finalRun.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(finalRun.getContext())
                .containsEntry("requirements.specPath", "specs/001-core-url-shortener/spec.md")
                .containsEntry("design.designPath", "specs/001-core-url-shortener/plan.md")
                .containsEntry("implementation.commit", "abc1234")
                .containsEntry("testing.testReport", "target/surefire-reports")
                .containsEntry("documentation.docsPath", "docs/architecture.md");
    }
}
