package com.urlshortener.orchestrator.metrics;

public record AggregateMetrics(
        long totalRuns,
        long runningRuns,
        long completedRuns,
        long failedRuns,
        long cancelledRuns,
        double successRate,
        long totalNodeExecutions,
        long totalRetries,
        double retryFrequency,
        long rolledBackNodeCount,
        double rollbackFrequency,
        Double avgEndToEndLatencyMillis,
        Double avgMttrMillis
) {
}
