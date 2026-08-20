package com.urlshortener.orchestrator.metrics;

import com.urlshortener.orchestrator.domain.RunStatus;
import com.urlshortener.orchestrator.domain.WorkflowRunEntity;
import com.urlshortener.orchestrator.engine.WorkflowEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MetricsServiceTest {

    @Autowired
    private WorkflowEngine engine;

    @Autowired
    private MetricsService metricsService;

    @Test
    void runMetrics_reflectRetriesAndRollbacksFromARecoveredThenFailedRun() {
        WorkflowRunEntity run = engine.startRun("test-diamond", Map.of(), "tester");
        String runId = run.getId();

        engine.complete(runId, "A", Map.of(), "agent");
        // One retry on B (transient failure), then it succeeds.
        engine.fail(runId, "B", "flaky build", "agent");
        engine.complete(runId, "B", Map.of(), "agent");
        engine.fail(runId, "C", "docs generator crashed", "agent"); // fallback path
        engine.complete(runId, "C_FALLBACK", Map.of(), "agent");
        engine.reject(runId, "D", "reviewer1", "not ready");

        RunMetrics metrics = metricsService.forRun(runId);
        assertThat(metrics.status()).isEqualTo(RunStatus.FAILED);
        assertThat(metrics.retryCount()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.rollbackCount()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.latencyMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aggregateMetrics_computeSuccessRateAcrossRuns() {
        WorkflowRunEntity completedRun = engine.startRun("test-gate", Map.of("blockNode", "false"), "tester");
        engine.complete(completedRun.getId(), "only", Map.of(), "agent");
        assertThat(engine.getRun(completedRun.getId()).getStatus()).isEqualTo(RunStatus.COMPLETED);

        WorkflowRunEntity failedRun = engine.startRun("test-gate", Map.of("blockNode", "true"), "tester");
        assertThat(engine.getRun(failedRun.getId()).getStatus()).isEqualTo(RunStatus.FAILED);

        AggregateMetrics aggregate = metricsService.aggregate();
        assertThat(aggregate.totalRuns()).isGreaterThanOrEqualTo(2);
        assertThat(aggregate.completedRuns()).isGreaterThanOrEqualTo(1);
        assertThat(aggregate.failedRuns()).isGreaterThanOrEqualTo(1);
        assertThat(aggregate.successRate()).isBetween(0.0, 1.0);
    }
}
