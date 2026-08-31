package com.urlshortener.orchestrator.engine;

import com.urlshortener.orchestrator.domain.EventType;
import com.urlshortener.orchestrator.domain.RunStatus;
import com.urlshortener.orchestrator.engine.executor.ScriptedNodeExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives the {@code test-autonomous} fixture end-to-end with a {@link ScriptedNodeExecutor} on the
 * real executor pool. Proves: non-gate nodes are executed without a REST callback; human approval
 * gates still block; and a scripted failure still runs the retry ladder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(WorkflowEngineAutonomousTest.AutonomousTestConfig.class)
class WorkflowEngineAutonomousTest {

    @Autowired
    private WorkflowEngine engine;

    @Autowired
    private ScriptedNodeExecutor scripted;

    private String nodeStatus(String runId, String nodeId) {
        return engine.getNodes(runId).stream()
                .filter(n -> n.getNodeId().equals(nodeId))
                .findFirst().orElseThrow().getStatus().name();
    }

    private List<EventType> auditTypes(String runId) {
        return engine.getAudit(runId).stream().map(e -> e.getEventType()).toList();
    }

    @Test
    void autonomousRun_executesNonGateNodesWithoutCallbacks_andStopsAtHumanGates() {
        scripted.complete("design", Map.of("designPath", "specs/x/plan.md"))
                .complete("impl", Map.of("commit", "abc1234"))
                .complete("test", Map.of("testReport", "target/surefire-reports"))
                .complete("docs", Map.of("docsPath", "docs/architecture.md"));

        var run = engine.startRun("test-autonomous", Map.of(), "product-owner", true);
        String runId = run.getId();

        // Autonomy stops at the first human gate.
        assertThat(nodeStatus(runId, "reqs")).isEqualTo("AWAITING_APPROVAL");
        assertThat(nodeStatus(runId, "design")).isEqualTo("PENDING");

        engine.approve(runId, "reqs", "product-owner", "clear", Map.of("specPath", "specs/x/spec.md"));

        // design -> impl -> {test, docs} all run via the scripted executor, no complete() calls here.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(nodeStatus(runId, "release")).isEqualTo("AWAITING_APPROVAL"));
        assertThat(nodeStatus(runId, "design")).isEqualTo("COMPLETED");
        assertThat(nodeStatus(runId, "impl")).isEqualTo("COMPLETED");
        assertThat(nodeStatus(runId, "test")).isEqualTo("COMPLETED");
        assertThat(nodeStatus(runId, "docs")).isEqualTo("COMPLETED");

        engine.approve(runId, "release", "release-manager", "ship it", Map.of());

        var finalRun = engine.getRun(runId);
        assertThat(finalRun.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(finalRun.getContext())
                .containsEntry("design.designPath", "specs/x/plan.md")
                .containsEntry("impl.commit", "abc1234")
                .containsEntry("test.testReport", "target/surefire-reports")
                .containsEntry("docs.docsPath", "docs/architecture.md");
    }

    @Test
    void scriptedFailureOnAutonomousNode_stillDrivesTheRetryLadder() {
        scripted.complete("design", Map.of("designPath", "specs/x/plan.md"))
                .complete("impl", Map.of("commit", "abc1234"))
                .failFirstThenComplete("test", Map.of("testReport", "target/surefire-reports"))
                .complete("docs", Map.of("docsPath", "docs/architecture.md"));

        var run = engine.startRun("test-autonomous", Map.of(), "po", true);
        String runId = run.getId();
        engine.approve(runId, "reqs", "po", "clear", Map.of("specPath", "specs/x/spec.md"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(auditTypes(runId)).contains(EventType.RETRY_ATTEMPTED);
            assertThat(nodeStatus(runId, "test")).isEqualTo("COMPLETED");
        });
        assertThat(nodeStatus(runId, "release")).isEqualTo("AWAITING_APPROVAL");
    }

    @TestConfiguration
    static class AutonomousTestConfig {

        @Bean
        ScriptedNodeExecutor scriptedNodeExecutor() {
            return new ScriptedNodeExecutor();
        }
    }
}
