package com.urlshortener.orchestrator.engine;

import com.urlshortener.orchestrator.definition.NodeDefinition;
import com.urlshortener.orchestrator.definition.WorkflowDefinition;
import com.urlshortener.orchestrator.definition.WorkflowDefinitionRegistry;
import com.urlshortener.orchestrator.domain.Actor;
import com.urlshortener.orchestrator.domain.AuditEventEntity;
import com.urlshortener.orchestrator.domain.EventType;
import com.urlshortener.orchestrator.domain.NodeExecutionEntity;
import com.urlshortener.orchestrator.domain.NodeStatus;
import com.urlshortener.orchestrator.domain.RunStatus;
import com.urlshortener.orchestrator.domain.WorkflowRunEntity;
import com.urlshortener.orchestrator.policy.PolicyEngine;
import com.urlshortener.orchestrator.policy.PolicyResult;
import com.urlshortener.orchestrator.repository.AuditEventRepository;
import com.urlshortener.orchestrator.repository.NodeExecutionRepository;
import com.urlshortener.orchestrator.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The orchestration engine: a DAG/state-machine executor over {@link WorkflowDefinition}s.
 *
 * <p>Design notes (see docs/architecture.md for the full write-up):
 * <ul>
 *   <li>The engine coordinates; it does not perform the underlying SDLC work itself. Nodes are
 *       dispatched to RUNNING (or AWAITING_APPROVAL for gated nodes) and an external caller — an
 *       agent that did the requirements/design/code/test/docs work, or a human approver — reports
 *       back via {@link #complete}/{@link #fail}/{@link #approve}/{@link #reject}. This models
 *       "controlled autonomy": the engine owns governance, the agent/human own the work.</li>
 *   <li>Parallel branches fall out naturally: once a shared dependency completes, every node whose
 *       deps are now satisfied is dispatched in the same pass, so multiple nodes can be RUNNING
 *       concurrently. A join node simply won't dispatch until all its deps are individually
 *       satisfied — that's the synchronization point.</li>
 *   <li>Per-run state transitions are serialized via a lock striped by runId, since two parallel
 *       branches can report completion at (near) the same time.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class WorkflowEngine {

    private final WorkflowRunRepository runRepo;
    private final NodeExecutionRepository nodeRepo;
    private final AuditEventRepository auditRepo;
    private final WorkflowDefinitionRegistry registry;
    private final PolicyEngine policyEngine;

    private final Map<String, Object> runLocks = new ConcurrentHashMap<>();

    private Object lockFor(String runId) {
        return runLocks.computeIfAbsent(runId, k -> new Object());
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public WorkflowRunEntity getRun(String runId) {
        return requireRun(runId);
    }

    @Transactional(readOnly = true)
    public List<NodeExecutionEntity> getNodes(String runId) {
        requireRun(runId);
        return nodeRepo.findByRunId(runId);
    }

    @Transactional(readOnly = true)
    public List<AuditEventEntity> getAudit(String runId) {
        requireRun(runId);
        return auditRepo.findByRunIdOrderByTimestampAsc(runId);
    }

    @Transactional(readOnly = true)
    public List<WorkflowRunEntity> listRuns() {
        return runRepo.findAll();
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Transactional
    public WorkflowRunEntity startRun(String workflowDefinitionId, Map<String, String> initialContext, String createdBy) {
        WorkflowDefinition def = registry.require(workflowDefinitionId);
        String runId = UUID.randomUUID().toString();
        synchronized (lockFor(runId)) {
            WorkflowRunEntity run = new WorkflowRunEntity(runId, def.getId(), createdBy, initialContext);
            runRepo.save(run);
            for (NodeDefinition nd : def.getNodes()) {
                nodeRepo.save(new NodeExecutionEntity(UUID.randomUUID().toString(), runId, nd.getId()));
            }
            audit(run, null, Actor.SYSTEM, EventType.RUN_STARTED,
                    "Run started for workflow '" + def.getId() + "'", null);
            dispatchReady(def, run);
            return run;
        }
    }

    @Transactional
    public WorkflowRunEntity complete(String runId, String nodeId, Map<String, String> artifacts, String actorName) {
        synchronized (lockFor(runId)) {
            WorkflowRunEntity run = requireRun(runId);
            WorkflowDefinition def = registry.require(run.getWorkflowDefinitionId());
            NodeDefinition nd = requireNodeDef(def, nodeId);
            NodeExecutionEntity node = requireNodeExec(runId, nodeId);

            if (node.getStatus() != NodeStatus.RUNNING) {
                throw new InvalidTransitionException(
                        "Node '" + nodeId + "' is " + node.getStatus() + "; cannot complete (must be RUNNING)");
            }
            if (artifacts != null) {
                node.getArtifacts().putAll(artifacts);
            }
            PolicyResult exit = policyEngine.checkExit(nd.getExitGate(), run, node);
            if (!exit.allowed()) {
                handlePolicyViolation(def, run, nd, node, "exit gate violation: " + exit.reason());
                return run;
            }
            finishNodeSuccessfully(run, nd, node, actorName);
            dispatchReady(def, run);
            return run;
        }
    }

    @Transactional
    public WorkflowRunEntity fail(String runId, String nodeId, String reason, String actorName) {
        synchronized (lockFor(runId)) {
            WorkflowRunEntity run = requireRun(runId);
            WorkflowDefinition def = registry.require(run.getWorkflowDefinitionId());
            NodeDefinition nd = requireNodeDef(def, nodeId);
            NodeExecutionEntity node = requireNodeExec(runId, nodeId);

            if (node.getStatus() != NodeStatus.RUNNING) {
                throw new InvalidTransitionException(
                        "Node '" + nodeId + "' is " + node.getStatus() + "; cannot report failure (must be RUNNING)");
            }
            node.setLastError(reason);

            boolean canRetry = node.getAttempt() < nd.getMaxRetries() + 1;
            if (canRetry) {
                node.setStatus(NodeStatus.RETRYING);
                nodeRepo.save(node);
                audit(run, nodeId, Actor.SYSTEM, EventType.RETRY_ATTEMPTED,
                        "Node failed (attempt " + node.getAttempt() + "/" + (nd.getMaxRetries() + 1) + "); retrying",
                        reason);
                dispatchNode(def, nd, run, node);
                return run;
            }

            audit(run, nodeId, Actor.SYSTEM, EventType.RETRY_EXHAUSTED,
                    "Retry budget exhausted after " + node.getAttempt() + " attempt(s)", reason);
            node.setStatus(NodeStatus.FAILED);
            node.setEndedAt(Instant.now());
            nodeRepo.save(node);
            audit(run, nodeId, Actor.SYSTEM, EventType.NODE_FAILED, "Node failed", reason);

            if (nd.getFallbackNodeId() != null) {
                audit(run, nodeId, Actor.SYSTEM, EventType.FALLBACK_TRIGGERED,
                        "Falling back to node '" + nd.getFallbackNodeId() + "'", reason);
                dispatchReady(def, run);
            } else {
                rollbackAndFailRun(def, run, nodeId);
            }
            return run;
        }
    }

    @Transactional
    public WorkflowRunEntity approve(String runId, String nodeId, String approver, String rationale, Map<String, String> artifacts) {
        synchronized (lockFor(runId)) {
            WorkflowRunEntity run = requireRun(runId);
            WorkflowDefinition def = registry.require(run.getWorkflowDefinitionId());
            NodeDefinition nd = requireNodeDef(def, nodeId);
            NodeExecutionEntity node = requireNodeExec(runId, nodeId);

            if (node.getStatus() != NodeStatus.AWAITING_APPROVAL) {
                throw new InvalidTransitionException(
                        "Node '" + nodeId + "' is " + node.getStatus() + "; cannot approve (must be AWAITING_APPROVAL)");
            }
            if (artifacts != null) {
                node.getArtifacts().putAll(artifacts);
            }
            PolicyResult exit = policyEngine.checkExit(nd.getExitGate(), run, node);
            if (!exit.allowed()) {
                handlePolicyViolation(def, run, nd, node, "exit gate violation: " + exit.reason());
                return run;
            }
            audit(run, nodeId, Actor.HUMAN, EventType.APPROVAL_GRANTED, "Approved by " + approver, rationale);
            finishNodeSuccessfully(run, nd, node, approver);
            dispatchReady(def, run);
            return run;
        }
    }

    @Transactional
    public WorkflowRunEntity reject(String runId, String nodeId, String approver, String rationale) {
        synchronized (lockFor(runId)) {
            WorkflowRunEntity run = requireRun(runId);
            WorkflowDefinition def = registry.require(run.getWorkflowDefinitionId());
            NodeExecutionEntity node = requireNodeExec(runId, nodeId);

            if (node.getStatus() != NodeStatus.AWAITING_APPROVAL) {
                throw new InvalidTransitionException(
                        "Node '" + nodeId + "' is " + node.getStatus() + "; cannot reject (must be AWAITING_APPROVAL)");
            }
            node.setStatus(NodeStatus.FAILED);
            node.setLastError("rejected by " + approver + ": " + rationale);
            node.setEndedAt(Instant.now());
            nodeRepo.save(node);
            audit(run, nodeId, Actor.HUMAN, EventType.APPROVAL_REJECTED, "Rejected by " + approver, rationale);
            audit(run, nodeId, Actor.SYSTEM, EventType.NODE_FAILED, "Node failed due to approval rejection", rationale);
            rollbackAndFailRun(def, run, nodeId);
            return run;
        }
    }

    @Transactional
    public WorkflowRunEntity pause(String runId) {
        synchronized (lockFor(runId)) {
            WorkflowRunEntity run = requireRun(runId);
            if (run.getStatus() != RunStatus.RUNNING) {
                throw new InvalidTransitionException("Run is " + run.getStatus() + "; can only pause a RUNNING run");
            }
            run.setStatus(RunStatus.PAUSED);
            runRepo.save(run);
            audit(run, null, Actor.HUMAN, EventType.RUN_PAUSED, "Run paused (safe-stop: in-flight nodes may still report)", null);
            return run;
        }
    }

    @Transactional
    public WorkflowRunEntity resume(String runId) {
        synchronized (lockFor(runId)) {
            WorkflowRunEntity run = requireRun(runId);
            if (run.getStatus() != RunStatus.PAUSED) {
                throw new InvalidTransitionException("Run is " + run.getStatus() + "; can only resume a PAUSED run");
            }
            WorkflowDefinition def = registry.require(run.getWorkflowDefinitionId());
            run.setStatus(RunStatus.RUNNING);
            runRepo.save(run);
            audit(run, null, Actor.HUMAN, EventType.RUN_RESUMED, "Run resumed", null);
            dispatchReady(def, run);
            return run;
        }
    }

    @Transactional
    public WorkflowRunEntity cancel(String runId, boolean rollback, String actorName) {
        synchronized (lockFor(runId)) {
            WorkflowRunEntity run = requireRun(runId);
            if (isTerminal(run.getStatus())) {
                throw new InvalidTransitionException("Run is already terminal (" + run.getStatus() + ")");
            }
            WorkflowDefinition def = registry.require(run.getWorkflowDefinitionId());

            for (NodeExecutionEntity node : nodeRepo.findByRunId(runId)) {
                if (isAbandonable(node.getStatus())) {
                    node.setStatus(NodeStatus.SKIPPED);
                    node.setEndedAt(Instant.now());
                    nodeRepo.save(node);
                    audit(run, node.getNodeId(), Actor.SYSTEM, EventType.NODE_SKIPPED, "Node abandoned: run cancelled", null);
                }
            }
            if (rollback) {
                rollbackCompletedNodes(def, run, "run cancellation by " + actorName);
            }
            run.setStatus(RunStatus.CANCELLED);
            run.setCompletedAt(Instant.now());
            runRepo.save(run);
            audit(run, null, Actor.HUMAN, EventType.RUN_CANCELLED,
                    "Run cancelled by " + actorName + (rollback ? " with rollback" : ""), null);
            return run;
        }
    }

    /** Dynamic re-planning: invalidate a completed decision node and everything downstream of it. */
    @Transactional
    public WorkflowRunEntity invalidate(String runId, String nodeId, String rationale, String actorName) {
        synchronized (lockFor(runId)) {
            WorkflowRunEntity run = requireRun(runId);
            WorkflowDefinition def = registry.require(run.getWorkflowDefinitionId());
            NodeExecutionEntity node = requireNodeExec(runId, nodeId);
            if (node.getStatus() != NodeStatus.COMPLETED) {
                throw new InvalidTransitionException(
                        "Node '" + nodeId + "' is " + node.getStatus() + "; can only invalidate a COMPLETED node");
            }

            Set<String> affected = def.transitiveDownstream(nodeId);
            affected.add(nodeId);
            int staleCount = 0;
            for (String affectedId : affected) {
                NodeExecutionEntity n = requireNodeExec(runId, affectedId);
                if (isReplannable(n.getStatus())) {
                    n.getArtifacts().keySet().forEach(k -> run.getContext().remove(affectedId + "." + k));
                    n.getArtifacts().clear();
                    n.setStatus(NodeStatus.STALE);
                    n.setAttempt(0);
                    n.setStartedAt(null);
                    n.setEndedAt(null);
                    n.setLastError(null);
                    nodeRepo.save(n);
                    staleCount++;
                }
            }
            if (run.getStatus() != RunStatus.PAUSED) {
                run.setStatus(RunStatus.RUNNING);
            }
            run.setCompletedAt(null);
            runRepo.save(run);
            audit(run, nodeId, actorName != null && !actorName.isBlank() ? Actor.HUMAN : Actor.SYSTEM,
                    EventType.REPLAN_TRIGGERED,
                    "Re-plan triggered: " + staleCount + " node(s) invalidated (upstream context for unaffected branches preserved)",
                    rationale);
            dispatchReady(def, run);
            return run;
        }
    }

    // ------------------------------------------------------------------
    // Internal engine mechanics
    // ------------------------------------------------------------------

    private void dispatchReady(WorkflowDefinition def, WorkflowRunEntity run) {
        if (run.getStatus() != RunStatus.RUNNING) {
            return;
        }
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (NodeDefinition nd : def.getNodes()) {
                NodeExecutionEntity node = requireNodeExec(run.getId(), nd.getId());
                if ((node.getStatus() == NodeStatus.PENDING || node.getStatus() == NodeStatus.STALE)
                        && depsSatisfied(def, run, nd)
                        && !isFallbackAwaitingTrigger(def, run, nd)) {
                    dispatchNode(def, nd, run, node);
                    progressed = true;
                }
            }
        }
        maybeCompleteRun(def, run);
    }

    private boolean depsSatisfied(WorkflowDefinition def, WorkflowRunEntity run, NodeDefinition nd) {
        for (String dep : nd.getDependsOn()) {
            if (!nodeSatisfied(def, run, dep)) {
                return false;
            }
        }
        return true;
    }

    private boolean nodeSatisfied(WorkflowDefinition def, WorkflowRunEntity run, String nodeId) {
        NodeExecutionEntity node = requireNodeExec(run.getId(), nodeId);
        if (node.getStatus() == NodeStatus.COMPLETED || node.getStatus() == NodeStatus.SKIPPED) {
            return true;
        }
        if (node.getStatus() == NodeStatus.FAILED) {
            NodeDefinition nd = requireNodeDef(def, nodeId);
            if (nd.getFallbackNodeId() != null) {
                NodeExecutionEntity fallback = requireNodeExec(run.getId(), nd.getFallbackNodeId());
                return fallback.getStatus() == NodeStatus.COMPLETED;
            }
        }
        return false;
    }

    /**
     * A node that some other node names as its {@code fallbackNodeId} must not dispatch just
     * because its own {@code dependsOn} is satisfied — it's a conditional alternate path, only
     * relevant once its primary node has exhausted retries and actually FAILED.
     */
    private boolean isFallbackAwaitingTrigger(WorkflowDefinition def, WorkflowRunEntity run, NodeDefinition nd) {
        List<NodeDefinition> primaries = def.getNodes().stream()
                .filter(other -> nd.getId().equals(other.getFallbackNodeId()))
                .toList();
        if (primaries.isEmpty()) {
            return false;
        }
        return primaries.stream().noneMatch(p -> requireNodeExec(run.getId(), p.getId()).getStatus() == NodeStatus.FAILED);
    }

    /** True for a fallback node still PENDING because the primary node it backs up succeeded directly. */
    private boolean isUntriggeredFallback(WorkflowDefinition def, WorkflowRunEntity run, NodeDefinition nd) {
        NodeExecutionEntity exec = requireNodeExec(run.getId(), nd.getId());
        return exec.getStatus() == NodeStatus.PENDING && isFallbackAwaitingTrigger(def, run, nd);
    }

    private void dispatchNode(WorkflowDefinition def, NodeDefinition nd, WorkflowRunEntity run, NodeExecutionEntity node) {
        PolicyResult entry = policyEngine.checkEntry(nd.getEntryGate(), run);
        if (!entry.allowed()) {
            handlePolicyViolation(def, run, nd, node, "entry gate violation: " + entry.reason());
            return;
        }
        node.setAttempt(node.getAttempt() + 1);
        node.setStartedAt(Instant.now());
        node.setEndedAt(null);
        node.setLastError(null);
        if (nd.isRequiresApproval()) {
            node.setStatus(NodeStatus.AWAITING_APPROVAL);
            nodeRepo.save(node);
            audit(run, nd.getId(), Actor.SYSTEM, EventType.NODE_AWAITING_APPROVAL,
                    "Node awaiting human approval (attempt " + node.getAttempt() + ")", null);
        } else {
            node.setStatus(NodeStatus.RUNNING);
            nodeRepo.save(node);
            audit(run, nd.getId(), Actor.SYSTEM, EventType.NODE_DISPATCHED,
                    "Node dispatched to agent (attempt " + node.getAttempt() + ")", null);
        }
    }

    private void finishNodeSuccessfully(WorkflowRunEntity run, NodeDefinition nd, NodeExecutionEntity node, String actorName) {
        node.setStatus(NodeStatus.COMPLETED);
        node.setEndedAt(Instant.now());
        nodeRepo.save(node);
        node.getArtifacts().forEach((k, v) -> run.getContext().put(nd.getId() + "." + k, v));
        runRepo.save(run);
        audit(run, nd.getId(), Actor.AGENT, EventType.NODE_COMPLETED, "Node completed" + (actorName != null ? " by " + actorName : ""), null);
    }

    private void handlePolicyViolation(WorkflowDefinition def, WorkflowRunEntity run, NodeDefinition nd, NodeExecutionEntity node, String reason) {
        node.setStatus(NodeStatus.FAILED);
        node.setLastError(reason);
        node.setEndedAt(Instant.now());
        nodeRepo.save(node);
        audit(run, nd.getId(), Actor.SYSTEM, EventType.POLICY_VIOLATION, "Policy gate rejected node", reason);
        audit(run, nd.getId(), Actor.SYSTEM, EventType.NODE_FAILED, "Node failed due to policy violation", reason);
        // Governance stops are not retried and do not fall back — they require a human/process fix upstream.
        rollbackAndFailRun(def, run, nd.getId());
    }

    private void rollbackAndFailRun(WorkflowDefinition def, WorkflowRunEntity run, String triggeringNodeId) {
        int rolledBack = rollbackCompletedNodes(def, run, "triggered by failure of '" + triggeringNodeId + "'");
        run.setStatus(RunStatus.FAILED);
        run.setCompletedAt(Instant.now());
        runRepo.save(run);
        audit(run, null, Actor.SYSTEM, EventType.RUN_FAILED,
                "Run failed" + (rolledBack > 0 ? " and " + rolledBack + " node(s) rolled back" : ""),
                "triggered by '" + triggeringNodeId + "'");
    }

    /** Reverse-order compensation over completed, compensable nodes. Returns how many were rolled back. */
    private int rollbackCompletedNodes(WorkflowDefinition def, WorkflowRunEntity run, String rationale) {
        List<NodeExecutionEntity> compensable = nodeRepo.findByRunId(run.getId()).stream()
                .filter(n -> n.getStatus() == NodeStatus.COMPLETED)
                .filter(n -> requireNodeDef(def, n.getNodeId()).isCompensation())
                .sorted(Comparator.comparing(NodeExecutionEntity::getEndedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        for (NodeExecutionEntity n : compensable) {
            n.setStatus(NodeStatus.ROLLED_BACK);
            n.setEndedAt(Instant.now());
            nodeRepo.save(n);
            audit(run, n.getNodeId(), Actor.SYSTEM, EventType.ROLLBACK_TRIGGERED,
                    "Compensating action invoked for node '" + n.getNodeId() + "'", rationale);
        }
        return compensable.size();
    }

    private void maybeCompleteRun(WorkflowDefinition def, WorkflowRunEntity run) {
        if (run.getStatus() != RunStatus.RUNNING) {
            return;
        }
        // A node counts as resolved if it's COMPLETED/SKIPPED, or FAILED with a completed fallback
        // (see nodeSatisfied) — otherwise a run that recovered entirely via fallback paths would
        // never reach COMPLETED just because the original branch node is still FAILED. A fallback
        // node that was never triggered (its primary succeeded directly) stays PENDING forever and
        // must not block completion either.
        boolean allResolved = def.getNodes().stream()
                .allMatch(nd -> nodeSatisfied(def, run, nd.getId()) || isUntriggeredFallback(def, run, nd));
        if (allResolved) {
            run.setStatus(RunStatus.COMPLETED);
            run.setCompletedAt(Instant.now());
            runRepo.save(run);
            audit(run, null, Actor.SYSTEM, EventType.RUN_COMPLETED, "All nodes completed successfully", null);
        }
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.CANCELLED;
    }

    private static boolean isAbandonable(NodeStatus status) {
        return status == NodeStatus.PENDING || status == NodeStatus.READY || status == NodeStatus.RUNNING
                || status == NodeStatus.AWAITING_APPROVAL || status == NodeStatus.RETRYING || status == NodeStatus.STALE;
    }

    private static boolean isReplannable(NodeStatus status) {
        return status == NodeStatus.COMPLETED || status == NodeStatus.AWAITING_APPROVAL
                || status == NodeStatus.RUNNING || status == NodeStatus.FAILED || status == NodeStatus.RETRYING;
    }

    private void audit(WorkflowRunEntity run, String nodeId, Actor actor, EventType type, String message, String rationale) {
        auditRepo.save(new AuditEventEntity(UUID.randomUUID().toString(), run.getId(), nodeId, actor, type, message, rationale));
    }

    private WorkflowRunEntity requireRun(String runId) {
        return runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Run not found: " + runId));
    }

    private NodeExecutionEntity requireNodeExec(String runId, String nodeId) {
        return nodeRepo.findByRunIdAndNodeId(runId, nodeId)
                .orElseThrow(() -> new NotFoundException("Node '" + nodeId + "' not found in run " + runId));
    }

    private NodeDefinition requireNodeDef(WorkflowDefinition def, String nodeId) {
        return def.node(nodeId)
                .orElseThrow(() -> new NotFoundException("Node '" + nodeId + "' not found in workflow " + def.getId()));
    }
}
