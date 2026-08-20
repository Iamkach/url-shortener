package com.urlshortener.orchestrator.metrics;

import com.urlshortener.orchestrator.domain.AuditEventEntity;
import com.urlshortener.orchestrator.domain.EventType;
import com.urlshortener.orchestrator.domain.NodeExecutionEntity;
import com.urlshortener.orchestrator.domain.NodeStatus;
import com.urlshortener.orchestrator.domain.RunStatus;
import com.urlshortener.orchestrator.domain.WorkflowRunEntity;
import com.urlshortener.orchestrator.engine.NotFoundException;
import com.urlshortener.orchestrator.repository.AuditEventRepository;
import com.urlshortener.orchestrator.repository.NodeExecutionRepository;
import com.urlshortener.orchestrator.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Derives reliability metrics purely from the {@code NodeExecutionEntity}/{@code AuditEventEntity}
 * audit trail — nothing is tracked separately, so the numbers are always consistent with the
 * queryable history. Covers the metrics the assignment calls out explicitly: success rate,
 * retry/rollback frequency, MTTR, and end-to-end latency.
 */
@Service
@RequiredArgsConstructor
public class MetricsService {

    private static final Set<EventType> RECOVERY_TRIGGER_EVENTS = Set.of(
            EventType.RETRY_ATTEMPTED, EventType.ROLLBACK_TRIGGERED, EventType.POLICY_VIOLATION, EventType.APPROVAL_REJECTED);

    private final WorkflowRunRepository runRepo;
    private final NodeExecutionRepository nodeRepo;
    private final AuditEventRepository auditRepo;

    @Transactional(readOnly = true)
    public AggregateMetrics aggregate() {
        List<WorkflowRunEntity> runs = runRepo.findAll();
        long total = runs.size();
        long running = runs.stream().filter(r -> r.getStatus() == RunStatus.RUNNING || r.getStatus() == RunStatus.PAUSED).count();
        long completed = runs.stream().filter(r -> r.getStatus() == RunStatus.COMPLETED).count();
        long failed = runs.stream().filter(r -> r.getStatus() == RunStatus.FAILED).count();
        long cancelled = runs.stream().filter(r -> r.getStatus() == RunStatus.CANCELLED).count();
        long decided = completed + failed + cancelled;
        double successRate = decided == 0 ? 0.0 : (double) completed / decided;

        List<NodeExecutionEntity> allNodes = nodeRepo.findAll();
        long totalNodeExecutions = allNodes.size();
        long totalRetries = allNodes.stream().mapToLong(n -> Math.max(0, n.getAttempt() - 1)).sum();
        double retryFrequency = totalNodeExecutions == 0 ? 0.0 : (double) totalRetries / totalNodeExecutions;
        long rolledBack = allNodes.stream().filter(n -> n.getStatus() == NodeStatus.ROLLED_BACK).count();
        double rollbackFrequency = totalNodeExecutions == 0 ? 0.0 : (double) rolledBack / totalNodeExecutions;

        List<Long> latencies = runs.stream()
                .filter(r -> r.getCompletedAt() != null)
                .map(r -> r.getCompletedAt().toEpochMilli() - r.getStartedAt().toEpochMilli())
                .toList();
        Double avgLatency = average(latencies);

        List<Long> mttrs = runs.stream()
                .map(r -> mttrForRun(r).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        Double avgMttr = average(mttrs);

        return new AggregateMetrics(total, running, completed, failed, cancelled, successRate,
                totalNodeExecutions, totalRetries, retryFrequency, rolledBack, rollbackFrequency,
                avgLatency, avgMttr);
    }

    @Transactional(readOnly = true)
    public RunMetrics forRun(String runId) {
        WorkflowRunEntity run = runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Run not found: " + runId));
        List<NodeExecutionEntity> nodes = nodeRepo.findByRunId(runId);
        int retryCount = nodes.stream().mapToInt(n -> Math.max(0, n.getAttempt() - 1)).sum();
        int rollbackCount = (int) nodes.stream().filter(n -> n.getStatus() == NodeStatus.ROLLED_BACK).count();
        Instant end = run.getCompletedAt() != null ? run.getCompletedAt() : Instant.now();
        long latencyMillis = end.toEpochMilli() - run.getStartedAt().toEpochMilli();
        Long mttr = mttrForRun(run).orElse(null);
        return new RunMetrics(runId, run.getStatus(), latencyMillis, retryCount, rollbackCount, mttr);
    }

    private java.util.Optional<Long> mttrForRun(WorkflowRunEntity run) {
        if (run.getStatus() != RunStatus.COMPLETED || run.getCompletedAt() == null) {
            return java.util.Optional.empty();
        }
        List<AuditEventEntity> events = auditRepo.findByRunIdOrderByTimestampAsc(run.getId());
        return events.stream()
                .filter(e -> RECOVERY_TRIGGER_EVENTS.contains(e.getEventType()))
                .findFirst()
                .map(first -> run.getCompletedAt().toEpochMilli() - first.getTimestamp().toEpochMilli());
    }

    private Double average(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }
}
