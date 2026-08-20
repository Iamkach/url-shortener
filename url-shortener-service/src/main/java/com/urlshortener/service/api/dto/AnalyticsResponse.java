package com.urlshortener.service.api.dto;

import java.time.Instant;

public record AnalyticsResponse(
        String shortCode,
        long totalClicks,
        Instant lastAccessedAt
) {
}
