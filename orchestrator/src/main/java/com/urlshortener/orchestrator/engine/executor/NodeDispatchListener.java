package com.urlshortener.orchestrator.engine.executor;

import com.urlshortener.orchestrator.definition.NodeDefinition;
import com.urlshortener.orchestrator.definition.WorkflowDefinition;
import com.urlshortener.orchestrator.definition.WorkflowDefinitionRegistry;
import com.urlshortener.orchestrator.domain.NodeExecutionEntity;
import com.urlshortener.orchestrator.domain.NodeStatus;
import com.urlshortener.orchestrator.domain.WorkflowRunEntity;
import com.urlshortener.orchestrator.engine.WorkflowEngine;
import com.urlshortener.orchestrator.repository.NodeExecutionRepository;
import com.urlshortener.orchestrator.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Bridges a just-dispatched node to its {@link NodeExecutor} on autonomous runs. Runs after the
 * engine transaction that set the node {@code RUNNING} has committed, so the pool thread sees a
 * consistent row; the executor's result is fed back through the engine's normal
 * {@code complete}/{@code fail} entry points, which re-acquire the per-run lock and run every gate.
 *
 * <p>Manual nodes and non-autonomous runs are filtered out synchronously here, so the default
 * (governed) execution path never touches the pool.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NodeDispatchListener {

    private final WorkflowRunRepository runRepo;
    private final NodeExecutionRepository nodeRepo;
    private final WorkflowDefinitionRegistry definitions;
    private final NodeExecutorRegistry executors;
    private final WorkflowEngine engine;
    private final Executor nodeExecutorPool;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDispatch(NodeDispatchedEvent event) {
        Optional<WorkflowRunEntity> maybeRun = runRepo.findById(event.runId());
        if (maybeRun.isEmpty() || !maybeRun.get().isAutonomous()) {
            return;
        }
        WorkflowDefinition def = definitions.require(maybeRun.get().getWorkflowDefinitionId());
        NodeDefinition node = def.node(event.nodeId()).orElse(null);
        if (node == null || executors.isManual(node)) {
            return;
        }
        nodeExecutorPool.execute(() -> runNode(event, node));
    }

    void runNode(NodeDispatchedEvent event, NodeDefinition node) {
        NodeExecutor executor = executors.resolve(node);
        Map<String, String> context = snapshotContextIfStillRunning(event);
        if (context == null) {
            return; // node already left RUNNING (a REST callback, a cancel, a re-plan) — nothing to do
        }
        NodeExecutionResult result;
        try {
            result = executor.execute(new NodeExecutionRequest(event.runId(), node, context));
        } catch (RuntimeException e) {
            log.warn("Executor '{}' threw for node '{}' in run {}", executor.id(), node.getId(), event.runId(), e);
            result = NodeExecutionResult.fail("executor '" + executor.id() + "' error: " + e.getMessage());
        }
        feedBack(event, executor.id(), result);
    }

    private Map<String, String> snapshotContextIfStillRunning(NodeDispatchedEvent event) {
        NodeExecutionEntity node = nodeRepo.findByRunIdAndNodeId(event.runId(), event.nodeId()).orElse(null);
        if (node == null || node.getStatus() != NodeStatus.RUNNING) {
            return null;
        }
        return runRepo.findById(event.runId())
                .map(run -> Map.copyOf(run.getContext()))
                .orElse(null);
    }

    private void feedBack(NodeDispatchedEvent event, String executorId, NodeExecutionResult result) {
        try {
            if (result.outcome() == NodeExecutionResult.Outcome.COMPLETE) {
                // executorId is the short actor label ("agent"); the free-text notes go to the
                // wide `rationale` column, not the 255-char audit `message`.
                engine.complete(event.runId(), event.nodeId(), result.artifacts(), executorId, result.notes());
            } else {
                engine.fail(event.runId(), event.nodeId(), result.notes(), executorId);
            }
        } catch (RuntimeException e) {
            // The node may have been completed/failed/re-planned by another path in the meantime.
            log.debug("Feed-back for node '{}' in run {} rejected by engine: {}", event.nodeId(), event.runId(), e.getMessage());
        }
    }
}
