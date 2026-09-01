package com.urlshortener.orchestrator.api;

import com.urlshortener.orchestrator.api.dto.ApproveRequest;
import com.urlshortener.orchestrator.api.dto.AuditEventResponse;
import com.urlshortener.orchestrator.api.dto.CancelRequest;
import com.urlshortener.orchestrator.api.dto.CompleteRequest;
import com.urlshortener.orchestrator.api.dto.FailRequest;
import com.urlshortener.orchestrator.api.dto.InvalidateRequest;
import com.urlshortener.orchestrator.api.dto.RejectRequest;
import com.urlshortener.orchestrator.api.dto.RunResponse;
import com.urlshortener.orchestrator.api.dto.StartRunRequest;
import com.urlshortener.orchestrator.domain.WorkflowRunEntity;
import com.urlshortener.orchestrator.engine.WorkflowEngine;
import com.urlshortener.orchestrator.metrics.MetricsService;
import com.urlshortener.orchestrator.metrics.RunMetrics;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/runs")
@RequiredArgsConstructor
public class RunController {

    private final WorkflowEngine engine;
    private final MetricsService metricsService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RunResponse start(@Valid @RequestBody StartRunRequest request) {
        WorkflowRunEntity run = engine.startRun(request.workflowDefinitionId(), request.initialContext(),
                request.createdBy(), request.autonomous());
        return toResponse(run.getId());
    }

    @GetMapping
    public List<RunResponse> list() {
        return engine.listRuns().stream().map(r -> toResponse(r.getId())).toList();
    }

    @GetMapping("/{runId}")
    public RunResponse get(@PathVariable String runId) {
        return toResponse(runId);
    }

    @GetMapping("/{runId}/audit")
    public List<AuditEventResponse> audit(@PathVariable String runId) {
        return engine.getAudit(runId).stream().map(AuditEventResponse::from).toList();
    }

    @GetMapping("/{runId}/metrics")
    public RunMetrics metrics(@PathVariable String runId) {
        return metricsService.forRun(runId);
    }

    @PostMapping("/{runId}/pause")
    public RunResponse pause(@PathVariable String runId) {
        engine.pause(runId);
        return toResponse(runId);
    }

    @PostMapping("/{runId}/resume")
    public RunResponse resume(@PathVariable String runId) {
        engine.resume(runId);
        return toResponse(runId);
    }

    @PostMapping("/{runId}/cancel")
    public RunResponse cancel(@PathVariable String runId, @RequestBody(required = false) CancelRequest request) {
        CancelRequest req = request != null ? request : new CancelRequest(false, "unspecified");
        engine.cancel(runId, req.rollback(), req.actor() != null ? req.actor() : "unspecified");
        return toResponse(runId);
    }

    @PostMapping("/{runId}/nodes/{nodeId}/approve")
    public RunResponse approve(@PathVariable String runId, @PathVariable String nodeId, @Valid @RequestBody ApproveRequest request) {
        engine.approve(runId, nodeId, request.approver(), request.rationale(), request.artifacts());
        return toResponse(runId);
    }

    @PostMapping("/{runId}/nodes/{nodeId}/reject")
    public RunResponse reject(@PathVariable String runId, @PathVariable String nodeId, @Valid @RequestBody RejectRequest request) {
        engine.reject(runId, nodeId, request.approver(), request.rationale());
        return toResponse(runId);
    }

    @PostMapping("/{runId}/nodes/{nodeId}/complete")
    public RunResponse complete(@PathVariable String runId, @PathVariable String nodeId, @RequestBody(required = false) CompleteRequest request) {
        CompleteRequest req = request != null ? request : new CompleteRequest(null, null);
        engine.complete(runId, nodeId, req.artifacts(), req.actor());
        return toResponse(runId);
    }

    @PostMapping("/{runId}/nodes/{nodeId}/fail")
    public RunResponse fail(@PathVariable String runId, @PathVariable String nodeId, @Valid @RequestBody FailRequest request) {
        engine.fail(runId, nodeId, request.reason(), request.actor());
        return toResponse(runId);
    }

    @PostMapping("/{runId}/nodes/{nodeId}/invalidate")
    public RunResponse invalidate(@PathVariable String runId, @PathVariable String nodeId, @Valid @RequestBody InvalidateRequest request) {
        engine.invalidate(runId, nodeId, request.rationale(), request.actor());
        return toResponse(runId);
    }

    private RunResponse toResponse(String runId) {
        return RunResponse.from(engine.getRun(runId), engine.getNodes(runId));
    }
}
