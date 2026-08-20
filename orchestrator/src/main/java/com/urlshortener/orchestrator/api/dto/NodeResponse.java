package com.urlshortener.orchestrator.api.dto;

import com.urlshortener.orchestrator.domain.NodeExecutionEntity;
import com.urlshortener.orchestrator.domain.NodeStatus;

import java.time.Instant;
import java.util.Map;

public record NodeResponse(
        String nodeId,
        NodeStatus status,
        int attempt,
        Instant startedAt,
        Instant endedAt,
        String lastError,
        Map<String, String> artifacts
) {
    public static NodeResponse from(NodeExecutionEntity e) {
        return new NodeResponse(e.getNodeId(), e.getStatus(), e.getAttempt(), e.getStartedAt(),
                e.getEndedAt(), e.getLastError(), e.getArtifacts());
    }
}
