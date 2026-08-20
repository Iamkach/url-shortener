package com.urlshortener.orchestrator.metrics;

import com.urlshortener.orchestrator.domain.RunStatus;

public record RunMetrics(
        String runId,
        RunStatus status,
        long latencyMillis,
        int retryCount,
        int rollbackCount,
        Long mttrMillis
) {
}
