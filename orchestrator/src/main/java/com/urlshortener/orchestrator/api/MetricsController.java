package com.urlshortener.orchestrator.api;

import com.urlshortener.orchestrator.metrics.AggregateMetrics;
import com.urlshortener.orchestrator.metrics.MetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsService metricsService;

    @GetMapping
    public AggregateMetrics aggregate() {
        return metricsService.aggregate();
    }
}
