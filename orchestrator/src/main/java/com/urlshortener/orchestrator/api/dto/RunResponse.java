package com.urlshortener.orchestrator.api.dto;

import com.urlshortener.orchestrator.domain.NodeExecutionEntity;
import com.urlshortener.orchestrator.domain.RunStatus;
import com.urlshortener.orchestrator.domain.WorkflowRunEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RunResponse(
        String id,
        String workflowDefinitionId,
        RunStatus status,
        Instant startedAt,
        Instant completedAt,
        String createdBy,
        Map<String, String> context,
        List<NodeResponse> nodes
) {
    public static RunResponse from(WorkflowRunEntity run, List<NodeExecutionEntity> nodes) {
        return new RunResponse(run.getId(), run.getWorkflowDefinitionId(), run.getStatus(),
                run.getStartedAt(), run.getCompletedAt(), run.getCreatedBy(), run.getContext(),
                nodes.stream().map(NodeResponse::from).toList());
    }
}
