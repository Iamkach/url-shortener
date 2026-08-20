package com.urlshortener.orchestrator.engine;

import com.urlshortener.orchestrator.domain.EventType;
import com.urlshortener.orchestrator.domain.NodeExecutionEntity;
import com.urlshortener.orchestrator.domain.RunStatus;
import com.urlshortener.orchestrator.domain.WorkflowRunEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the "test-diamond" fixture: A -> {B, C} -> D, where C has a fallback and D is a
 * human-approval join. Covers parallel dispatch + synchronization, bounded retry, fallback,
 * rollback, approval rejection, dynamic re-plan, and safe-stop (pause/resume).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkflowEngineDiamondTest {

    @Autowired
    private WorkflowEngine engine;

    private String nodeStatus(String runId, String nodeId) {
        return engine.getNodes(runId).stream()
                .filter(n -> n.getNodeId().equals(nodeId))
                .findFirst().orElseThrow().getStatus().name();
    }

    private NodeExecutionEntity node(String runId, String nodeId) {
        return engine.getNodes(runId).stream()
                .filter(n -> n.getNodeId().equals(nodeId))
                .findFirst().orElseThrow();
    }

    @Test
    void parallelBranchesSyncAtJoin_withFallbackOnOneBranch() {
        WorkflowRunEntity run = engine.startRun("test-diamond", Map.of(), "tester");
        String runId = run.getId();

        assertThat(nodeStatus(runId, "A")).isEqualTo("RUNNING");
        engine.complete(runId, "A", Map.of(), "agent");

        // B and C both depend only on A -> both dispatched together (parallel + synchronization).
        assertThat(nodeStatus(runId, "B")).isEqualTo("RUNNING");
        assertThat(nodeStatus(runId, "C")).isEqualTo("RUNNING");

        engine.complete(runId, "B", Map.of(), "agent");
        // C has maxRetries=0 and a fallback: first failure exhausts retries immediately.
        engine.fail(runId, "C", "documentation generator crashed", "agent");
        assertThat(nodeStatus(runId, "C")).isEqualTo("FAILED");
        assertThat(nodeStatus(runId, "C_FALLBACK")).isEqualTo("RUNNING");

        // D depends on B + C; C failed but has a completed fallback, so D still isn't ready yet.
        assertThat(nodeStatus(runId, "D")).isEqualTo("PENDING");

        engine.complete(runId, "C_FALLBACK", Map.of(), "agent");
        assertThat(nodeStatus(runId, "D")).isEqualTo("AWAITING_APPROVAL");

        engine.approve(runId, "D", "reviewer1", "looks good", Map.of());
        assertThat(engine.getRun(runId).getStatus()).isEqualTo(RunStatus.COMPLETED);

        List<EventType> eventTypes = engine.getAudit(runId).stream().map(e -> e.getEventType()).toList();
        assertThat(eventTypes).contains(EventType.FALLBACK_TRIGGERED, EventType.APPROVAL_GRANTED, EventType.RUN_COMPLETED);
    }

    @Test
    void retriesExhausted_withNoFallback_triggersRollbackAndFailsRun() {
        WorkflowRunEntity run = engine.startRun("test-diamond", Map.of(), "tester");
        String runId = run.getId();

        engine.complete(runId, "A", Map.of(), "agent");
        assertThat(node(runId, "B").getAttempt()).isEqualTo(1);

        // B allows maxRetries=2 -> 3 total attempts before permanent failure.
        engine.fail(runId, "B", "flaky build", "agent");
        assertThat(nodeStatus(runId, "B")).isEqualTo("RUNNING");
        assertThat(node(runId, "B").getAttempt()).isEqualTo(2);

        engine.fail(runId, "B", "flaky build again", "agent");
        assertThat(node(runId, "B").getAttempt()).isEqualTo(3);

        engine.fail(runId, "B", "still failing", "agent");
        assertThat(nodeStatus(runId, "B")).isEqualTo("FAILED");

        WorkflowRunEntity finalRun = engine.getRun(runId);
        assertThat(finalRun.getStatus()).isEqualTo(RunStatus.FAILED);
        // A is compensation=true and was COMPLETED -> must be rolled back.
        assertThat(nodeStatus(runId, "A")).isEqualTo("ROLLED_BACK");

        List<EventType> eventTypes = engine.getAudit(runId).stream().map(e -> e.getEventType()).toList();
        assertThat(eventTypes).contains(EventType.RETRY_ATTEMPTED, EventType.RETRY_EXHAUSTED,
                EventType.ROLLBACK_TRIGGERED, EventType.RUN_FAILED);
    }

    @Test
    void approvalRejection_triggersRollbackOfCompletedCompensableNodes() {
        WorkflowRunEntity run = engine.startRun("test-diamond", Map.of(), "tester");
        String runId = run.getId();

        engine.complete(runId, "A", Map.of(), "agent");
        engine.complete(runId, "B", Map.of(), "agent");
        engine.complete(runId, "C", Map.of(), "agent");
        assertThat(nodeStatus(runId, "D")).isEqualTo("AWAITING_APPROVAL");

        engine.reject(runId, "D", "reviewer1", "not ready for release");

        WorkflowRunEntity finalRun = engine.getRun(runId);
        assertThat(finalRun.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(nodeStatus(runId, "A")).isEqualTo("ROLLED_BACK");
        assertThat(nodeStatus(runId, "B")).isEqualTo("ROLLED_BACK");
    }

    @Test
    void invalidatingCompletedNode_marksTransitiveDownstreamStaleAndRedispatches() {
        WorkflowRunEntity run = engine.startRun("test-diamond", Map.of(), "tester");
        String runId = run.getId();

        engine.complete(runId, "A", Map.of(), "agent");
        engine.complete(runId, "B", Map.of(), "agent");
        engine.complete(runId, "C", Map.of(), "agent");
        engine.approve(runId, "D", "reviewer1", "ok", Map.of());
        assertThat(engine.getRun(runId).getStatus()).isEqualTo(RunStatus.COMPLETED);

        engine.invalidate(runId, "A", "upstream requirement changed after review", "human-reviewer");

        WorkflowRunEntity afterReplan = engine.getRun(runId);
        assertThat(afterReplan.getStatus()).isEqualTo(RunStatus.RUNNING);
        // A has no deps, so it's redispatched immediately within the same invalidate() call.
        assertThat(nodeStatus(runId, "A")).isEqualTo("RUNNING");
        // Downstream nodes are stale until A completes again.
        assertThat(nodeStatus(runId, "B")).isEqualTo("STALE");
        assertThat(nodeStatus(runId, "C")).isEqualTo("STALE");
        assertThat(nodeStatus(runId, "D")).isEqualTo("STALE");

        List<EventType> eventTypes = engine.getAudit(runId).stream().map(e -> e.getEventType()).toList();
        assertThat(eventTypes).contains(EventType.REPLAN_TRIGGERED);
    }

    @Test
    void pause_haltsNewDispatchButAllowsInFlightNodesToReport() {
        WorkflowRunEntity run = engine.startRun("test-diamond", Map.of(), "tester");
        String runId = run.getId();

        engine.complete(runId, "A", Map.of(), "agent");
        assertThat(nodeStatus(runId, "B")).isEqualTo("RUNNING");
        assertThat(nodeStatus(runId, "C")).isEqualTo("RUNNING");

        engine.pause(runId);
        assertThat(engine.getRun(runId).getStatus()).isEqualTo(RunStatus.PAUSED);

        // In-flight nodes can still report while paused (safe-stop, not a hard kill).
        engine.complete(runId, "B", Map.of(), "agent");
        engine.complete(runId, "C", Map.of(), "agent");
        // D would be ready now, but dispatch is halted while paused.
        assertThat(nodeStatus(runId, "D")).isEqualTo("PENDING");

        engine.resume(runId);
        assertThat(nodeStatus(runId, "D")).isEqualTo("AWAITING_APPROVAL");
    }
}
